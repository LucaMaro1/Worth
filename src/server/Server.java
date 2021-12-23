package server;

import com.fasterxml.jackson.databind.*;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.rmi.*;
import java.rmi.ConnectException;
import java.rmi.server.*;
import java.util.*;

public class Server extends RemoteServer implements ServerInterface {
    private static final String USERS_FILENAME = "./users.json";
    private static final String PROJECT_MEMBERS_FILENAME = "members.txt";
    private static final String PROJECT_CARDS_FILENAME = "cards.json";
    private static final int bufflen = 4096;
    private static final int multicastPort = 2000;

    private final int port;
    private final List<NotifyEventInterface> clients;

    private final List<String> multicastAddressInUse;
    private final Map<Utente, Boolean> registeredUsers;
    private final List<Progetto> projects;

    public Server(int port) {
        super();
        this.port = port;
        clients = new ArrayList<NotifyEventInterface>();

        multicastAddressInUse = new ArrayList<String>();
        registeredUsers = new HashMap<Utente, Boolean>();
        projects = new ArrayList<Progetto>();
    }

    public void start() throws IOException {
        //backup

        //recupero utenti
        File usersFile = new File(USERS_FILENAME);
        if(usersFile.exists()) {
            ObjectMapper objectMapper = new ObjectMapper();
            FileReader fileReader = new FileReader(usersFile);
            JsonNode utentiJsonNode = objectMapper.readTree(fileReader).get("utenti");

            for(JsonNode objNode : utentiJsonNode) {
                registeredUsers.put(new Utente(objNode.get("username").asText(), objNode.get("password").asText()), false);
            }
        }

        File currentDirectory = new File(".");
        File[] fileList = currentDirectory.listFiles();
        //recupero progetti
        if(fileList != null) {
            for (File f : fileList) {
                List<String> usernameMembri = new ArrayList<String>();
                if(f.isDirectory()) {
                    //recupero membri progetto
                    BufferedReader reader = new BufferedReader(new FileReader(f.getPath() + "/" + PROJECT_MEMBERS_FILENAME));
                    String membro = reader.readLine();
                    while(membro != null) {
                        usernameMembri.add(membro);
                        membro = reader.readLine();
                    }
                    reader.close();

                    List<Utente> membri = new ArrayList<Utente>();
                    for (String nomeUtente : usernameMembri) {
                        for (Utente u : registeredUsers.keySet()) {
                            if(u.getUsername().equals(nomeUtente)) {
                                membri.add(u);
                            }
                        }
                    }

                    Utente primoMembro = membri.remove(0);
                    String multicastAddress = getNextAvailableAddress();
                    Progetto progetto = new Progetto(f.getName(), primoMembro, InetAddress.getByName(multicastAddress), multicastPort);
                    multicastAddressInUse.add(multicastAddress);
                    projects.add(progetto);
                    progetto.aggiungiMembri(membri);

                    //recupero cards del progetto
                    ObjectMapper objectMapper = new ObjectMapper();
                    FileReader fileReaderCards = new FileReader(f.getPath() + "/" + PROJECT_CARDS_FILENAME);
                    JsonNode cardsJsonNode = objectMapper.readTree(fileReaderCards).get("cards");

                    if(cardsJsonNode != null) {
                        for(JsonNode objNode : cardsJsonNode) {
                            Card card = new Card(objNode.get("nomeCard").asText(), objNode.get("descrizione").asText());
                            progetto.aggiungiCard(card);

                            BufferedReader cardStoryReader = new BufferedReader(new FileReader(f.getPath() + "/" + card.getNomeCard() + ".txt"));
                            String line = cardStoryReader.readLine();
                            String stato = "";
                            while(line != null) {
                                stato = line;
                                line = cardStoryReader.readLine();
                            }
                            switch (stato) {
                                case "TODO":
                                    break;
                                case "INPROGRESS":
                                    progetto.spostaCarta(card.getNomeCard(), "TODO", "INPROGRESS");
                                    break;
                                case "TOBEREVISED":
                                    progetto.spostaCarta(card.getNomeCard(), "TODO", "TOBEREVISED");
                                    break;
                                case "DONE":
                                    progetto.spostaCarta(card.getNomeCard(), "TODO", "DONE");
                                    break;
                            }
                        }
                    }
                }
            }
        }

        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.socket().bind(new InetSocketAddress(this.port)); //stabilisco una connessione sulla porta scelta
        System.out.println("Server Listening on port " + this.port);
        serverSocketChannel.configureBlocking(false); //imposto comportamento non bloccante
        Selector sel = Selector.open();
        serverSocketChannel.register(sel, SelectionKey.OP_ACCEPT); //registro la socket sul canale per l'operazione di accettazione di connessioni

        boolean quit = false; //gestire la chiusura di connessione coi client
        Map<SocketChannel, String> utentePerConnessione = new HashMap<SocketChannel, String>(); //associo un utente ad ogni connessione per la gestione dei crash lato client
        while(true) {
            if(quit) {
                quit = false;
            }
            if(sel.select() == 0) {
                continue;
            }
            Set<SelectionKey> readyKeys = sel.selectedKeys();
            Iterator<SelectionKey> iterator = readyKeys.iterator();
            while(iterator.hasNext()) {
                SelectionKey key = iterator.next();
                iterator.remove();
                if(key.isAcceptable()) {
                    //accetta nuova connessione
                    ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
                    SocketChannel clientChannel = serverChannel.accept();
                    clientChannel.configureBlocking(false);

                    clientChannel.register(sel, SelectionKey.OP_READ); //preparazione alla lettura della richiesta del client
                }
                if(key.isReadable()) {
                    SocketChannel clientChannel = (SocketChannel) key.channel();
                    ByteBuffer buffer = ByteBuffer.allocate(bufflen);
                    clientChannel.read(buffer); //scrivo la richiesta sul buffer
                    String command = new String(buffer.array(), StandardCharsets.UTF_8);

                    String[] splitCommand = command.split(" "); //divido l'input dell'utente nel comando effettivo e nei suoi parametri
                    String response = "";
                    if(splitCommand.length == 1) { //utile per il quit
                        splitCommand[0] = splitCommand[0].substring(0, splitCommand[0].indexOf(0)); //per eliminare i caratteri di riempimento del buffer
                    }

                    switch(splitCommand[0]) { //comando effettivo
                        case "login":
                            if(splitCommand.length == 3) {
                                response = login(splitCommand[1], splitCommand[2].substring(0, splitCommand[2].indexOf(0)));
                                utentePerConnessione.put(clientChannel, splitCommand[1]);
                            } else {
                                response = "Unable to login: syntax error";
                            }
                            break;
                        case "logout":
                            if(splitCommand.length == 2) {
                                response = logout(splitCommand[1].substring(0, splitCommand[1].indexOf(0)));
                                utentePerConnessione.remove(clientChannel);
                            } else {
                                response = "Unable to logout: syntax error";
                            }
                            break;
                        case "create_project":
                            if(splitCommand.length == 3) {
                                response = createProject(splitCommand[1], splitCommand[2].substring(0, splitCommand[2].indexOf(0)));
                            } else {
                                response = "Unable to create project: syntax error";
                            }
                            break;
                        case "list_projects":
                            if(splitCommand.length == 2) {
                                response = listProjects(splitCommand[1].substring(0, splitCommand[1].indexOf(0)));
                            } else {
                                response = "Unable to get your projects: syntax error";
                            }
                            break;
                        case "add_member":
                            if(splitCommand.length == 4) {
                                response = addMember(splitCommand[1], splitCommand[2], splitCommand[3].substring(0, splitCommand[3].indexOf(0)));
                            } else {
                                response = "Unable to add member: syntax error";
                            }
                            break;
                        case "show_members":
                            if(splitCommand.length == 3) {
                                response = showMembers(splitCommand[1], splitCommand[2].substring(0, splitCommand[2].indexOf(0)));
                            } else {
                                response = "Unable to add member: syntax error";
                            }
                            break;
                        case "add_card":
                            if(splitCommand.length == 5) {
                                response = addCard(splitCommand[1], splitCommand[2], splitCommand[3], splitCommand[4].substring(0, splitCommand[4].indexOf(0)));
                            } else {
                                //se la descrizione comprende più di una parola deve essere compresa fra doppi apici
                                //quindi raggruppo tutto il testo fra doppi apici in una stringa sola
                                int i = 0;
                                String description = "";
                                for(int j = 0 ; j < splitCommand.length ; j++) {
                                    if(splitCommand[j].charAt(0) == '"') {
                                        i = j;
                                        break;
                                    }
                                }
                                int f = i;
                                for(int j = i ; j < splitCommand.length ; j++) {
                                    if(splitCommand[j].charAt(splitCommand[j].length() - 1) == '"') {
                                        f = j;
                                        break;
                                    }
                                }
                                description = splitCommand[i];
                                for(int j = (i + 1) ; j <= f ; j++) {
                                    description = description.concat(" " + splitCommand[j]);
                                }

                                if(splitCommand.length - (f - i) == 5) { //controllo se, dopo aver raggruppato la descrizione in un'unica stringa, la sintassi è rispettata
                                    response = addCard(splitCommand[1], splitCommand[2], description.substring(1, description.length() - 1), splitCommand[splitCommand.length - 1].substring(0, splitCommand[splitCommand.length - 1].indexOf(0)));
                                } else {
                                    response = "Unable to add Card: syntax error";
                                }
                            }
                            break;
                        case "show_cards":
                            if(splitCommand.length == 3) {
                                response = showCards(splitCommand[1], splitCommand[2].substring(0, splitCommand[2].indexOf(0)));
                            } else {
                                response = "Unable to show project cards: syntax error";
                            }
                            break;
                        case "show_card":
                            if(splitCommand.length == 4) {
                                response = showCard(splitCommand[1], splitCommand[2], splitCommand[3].substring(0, splitCommand[3].indexOf(0)));
                            } else {
                                response = "Unable to show card: syntax error";
                            }
                            break;
                        case "move_card":
                            if(splitCommand.length == 6) {
                                response = moveCard(splitCommand[1], splitCommand[2], splitCommand[3], splitCommand[4], splitCommand[5].substring(0, splitCommand[5].indexOf(0)));
                            } else {
                                response = "Unable to move card: syntax error";
                            }
                            break;
                        case "card_history":
                            if(splitCommand.length == 4) {
                                response = cardHistory(splitCommand[1], splitCommand[2], splitCommand[3].substring(0, splitCommand[3].indexOf(0)));
                            } else {
                                response = "Unable to show card history: syntax error";
                            }
                            break;
                        case "cancel_project":
                            if(splitCommand.length == 3) {
                                response = cancelProject(splitCommand[1], splitCommand[2].substring(0, splitCommand[2].indexOf(0)));
                            } else {
                                response = "Unable to cancel project: syntax error";
                            }
                            break;
                        case "quit":
                            ByteBuffer quitBuffer = ByteBuffer.allocate(bufflen);
                            response = "OK";
                            quitBuffer.put(response.getBytes());

                            //comunico al client di aver compreso la richiesta di terminare e chiudo la connessione
                            quitBuffer.flip();
                            clientChannel.write(quitBuffer);
                            clientChannel.close();
                            quit = true;
                            continue;
                        default:
                            //i comandi non riconosciuti sono gestiti dal client
                            //si entra nel blocco default nel caso il client si chiuda in modo anomalo
                            System.err.println("Client-side error occurred");

                            String usernameCrashed = utentePerConnessione.get(clientChannel);
                            Utente userCrashed = null;
                            for (Utente u : registeredUsers.keySet()) {
                                if(u.getUsername().equals(usernameCrashed)) {
                                    userCrashed = u;
                                }
                            }

                            //chiudo la connessione e setto l'utente crashato offline
                            if(userCrashed != null) {
                                registeredUsers.put(userCrashed, false);
                                updateLog(usernameCrashed, false);
                            }
                            utentePerConnessione.remove(clientChannel);
                            clientChannel.close();
                            key.cancel();
                            continue;
                    }

                    //preparazione all'invio della risposta al client
                    //in allegato inserisco il contenuto della risposta
                    clientChannel.register(sel, SelectionKey.OP_WRITE, response);
                }
                if(key.isWritable()) {
                    //ottengo la risposta da inviare
                    SocketChannel clientChannel = (SocketChannel) key.channel();
                    String response = (String) key.attachment();

                    //scrivo la risposta nel buffer
                    ByteBuffer buffer = ByteBuffer.allocate(bufflen);
                    buffer.put(response.getBytes());

                    //invio la risposta al client
                    buffer.flip();
                    clientChannel.write(buffer);
                    clientChannel.register(sel, SelectionKey.OP_READ); //preparazione ad una nuova lettura
                }
            }
        }
    }

    /**
     *
     * @param username username dell'utente che vuole effettuare il login
     * @param password password per verificare l'identità dell'utente
     * @return risposta del server per segnalare il successo o meno dell'operazione
     * @throws RemoteException se si verificano errori durante l'esecuzione della chiamata remota di updateLog
     */
    private String login(String username, String password) throws RemoteException {
        String response = "";
        boolean found = false;

        for (Utente user : registeredUsers.keySet()) {
            //controllo se esiste un account con quell'username
            if(user.getUsername().equals(username)) {
                found = true;
                //controllo se l'utente è già loggato
                if(registeredUsers.get(user)) {
                    response = "Unable to login: user already logged in";
                } else {
                    //controllo se la password è corretta
                    if(user.getPassword().equals(password)) {
                        registeredUsers.put(user, true);
                        response = username + " logged in";

                        updateLog(user.getUsername(), true);
                    } else {
                        response = "Unable to login: wrong password";
                    }
                }
            }
        }
        if(!found) {
            response = "Unable to login: user " + username + " doesn't exist";
        }

        return response;
    }

    /**
     *
     * @param username username dell'utente che vuole effettuare il logout
     * @return risposta del server per segnalare il successo o meno dell'operazione
     * @throws RemoteException se si verificano errori durante l'esecuzione della chiamata remota di updateLog
     */
    private String logout(String username) throws RemoteException {
        String response = "";
        boolean found = false;

        for (Utente user : registeredUsers.keySet()) {
            //controllo se esiste un account con quell'username
            if(user.getUsername().equals(username)) {
                found = true;
                if(registeredUsers.get(user)) {
                    registeredUsers.put(user, false);
                    response = username + " logged out";

                    updateLog(user.getUsername(), false);
                } else {
                    response = "Unable to logout: user not logged";
                }
            }
        }
        if(!found) {
            response = "Unable to logout: user " + username + " doesn't exist";
        }

        return response;
    }

    /**
     *
     * @param projectName nome del progetto che si vuole creare
     * @param username username dell'utente che ha chiesto la creazione
     * @return risposta del server per segnalare il successo o meno dell'operazione
     * @throws IOException se si verificano errori nella creazione e scrittura del file
     */
    private String createProject(String projectName, String username) throws IOException {
        //controllo se già esiste un progetto con quel nome
        for (Progetto p : projects) {
            if(projectName.equals(p.getNomeProg())) {
                return "Unable to create project: project named " + projectName + " already exists";
            }
        }

        //ottengo l'utente a partire dall'username
        Set<Utente> users = registeredUsers.keySet();
        Utente user = null;
        for (Utente u : users) {
            if(u.getUsername().equals(username)) {
                user = u;
            }
        }
        //creo il progetto
        String address = getNextAvailableAddress();
        if(address.equals("error")) {
            return "Unable to create project: error occurred during chat creation";
        }
        if(address.equals("finished")) {
            return "Unable to create project: number of total projects reached the limit";
        }
        multicastAddressInUse.add(address);
        Progetto progetto = new Progetto(projectName, user, InetAddress.getByName(address), multicastPort);
        projects.add(progetto);

        //creo la directory del progetto e tutti i file dove salvare i dati
        Path projDirPath = Paths.get("./" + projectName);
        Files.createDirectory(projDirPath);
        Files.createFile(Paths.get(projDirPath + "/" + PROJECT_MEMBERS_FILENAME));
        Files.createFile(Paths.get(projDirPath + "/" + PROJECT_CARDS_FILENAME));

        //aggiorno il file dei membri del progetto aggiungendo l'utente che l'ha creato
        File membersFile = new File(projDirPath + "/" + PROJECT_MEMBERS_FILENAME);
        BufferedWriter writer = new BufferedWriter(new FileWriter(membersFile, true));
        writer.write(username + "\n");
        writer.close();

        updateNewProject(user, progetto); //comunico al client di creare un nuovo thread per ricevere i messaggi della chat di progetto
        return "project " + projectName + " successfully created";
    }

    /**
     *
     * @param username username dell'utente che ha effettuato la richiesta
     * @return stringa che contiene i nomi di tutti i progetti di cui username è membro
     */
    private String listProjects(String username) {
        List<String> progetti = new ArrayList<String>();
        Utente user = null;
        //ottengo l'utente dall'username
        for (Utente u : registeredUsers.keySet()) {
            if(u.getUsername().equals(username)) {
                user = u;
            }
        }
        //inserisco nella lista tutti i progetti di cui l'utente è membro
        for (Progetto p : projects) {
            if(p.getMembriProg().contains(user)) {
                progetti.add(p.getNomeProg());
            }
        }
        return progetti.toString();
    }

    /**
     *
     * @param projectName nome del progetto al quale si vuole aggiungere un membro
     * @param newUsername username dell'utente che si vuole aggiungere al progetto
     * @param username username dell'utente che ha effettuato la richiesta
     * @return risposta del server per segnalare il successo o meno dell'operazione
     * @throws IOException se si verificano errori nella scrittura del file
     */
    private String addMember(String projectName, String newUsername, String username) throws IOException {
        //controllo che il progetto esista
        Progetto progetto = null;
        for(Progetto p : projects) {
            if(p.getNomeProg().equals(projectName)) {
                progetto = p;
                break;
            }
        }
        if(progetto == null) {
            return "Unable to add member: project " + projectName + " doesn't exist";
        }

        //controllo se l'utente ha il diritto di aggiungere membri a quel progetto
        //e se l'utente che si vuole aggiungere esiste e può essere aggiunto
        Utente user = null;
        Utente newUser = null;
        for(Utente u : registeredUsers.keySet()) {
            if(u.getUsername().equals(username)) {
                user = u;
            }
            if(u.getUsername().equals(newUsername)) {
                newUser = u;
            }
        }
        if(!progetto.getMembriProg().contains(user)) {
            return "Unable to add member: you aren't a member of project " + projectName;
        }
        if(newUser == null) {
            return "Unable to add member: user " + newUsername + " doesn't exist";
        }
        if(progetto.getMembriProg().contains(newUser)) {
            return "Unable to add member: " + newUsername + " is already part of project " + projectName;
        }

        //aggiungo il nuovo membro e aggiorno il file dei membri del progetto
        progetto.aggiungiMembro(newUser);
        BufferedWriter writer = new BufferedWriter(new FileWriter("./" + projectName + "/" + PROJECT_MEMBERS_FILENAME, true));
        writer.write(newUsername + "\n");
        writer.close();
        updateNewProject(newUser, progetto);
        return "OK";
    }

    /**
     *
     * @param projectName nome del progetto del quale si vogliono conoscere i membri
     * @param username nome dell'utente che richiede di conoscere i membri del progetto
     * @return in caso di risposta positiva una stringa contenente la lista degli username dei membri del progetto
     *         altrimenti una risposta di errore
     */
    private String showMembers(String projectName, String username) {
        //controllo che il progetto esista
        Progetto progetto = null;
        for(Progetto p : projects) {
            if(p.getNomeProg().equals(projectName)) {
                progetto = p;
                break;
            }
        }
        if(progetto == null) {
            return "Unable to show project members: project " + projectName + " doesn't exist";
        }

        //controllo se l'utente ha il diritto di sapere chi sono membri del progetto
        Utente user = null;
        for(Utente u : registeredUsers.keySet()) {
            if(u.getUsername().equals(username)) {
                user = u;
            }
        }
        if(!progetto.getMembriProg().contains(user)) {
            return "Unable to show project members: you aren't a member of project " + projectName;
        }

        //salvo tutti gli username dei membri in una lista da restituire all'utente
        List<String> membri = new ArrayList<String>();
        for (Utente u : progetto.getMembriProg()) {
            membri.add(u.getUsername());
        }
        return membri.toString();
    }

    /**
     *
     * @param project nome del progetto al quale si vuole aggiungere una card
     * @param cardName nome della card che si vuole aggiungere
     * @param description descrizione della card che si vuole aggiungere
     * @param username nome dell'utente che ha effetuato la richiesta
     * @return risposta del server per segnalare il successo o meno dell'operazione
     * @throws IOException se si verificano errori nella scrittura dei file
     */
    private String addCard(String project, String cardName, String description, String username) throws IOException {
        //controllo che il progetto esista
        Progetto progetto = null;
        for(Progetto p : projects) {
            if(p.getNomeProg().equals(project)) {
                progetto = p;
                break;
            }
        }
        if(progetto == null) {
            return "Unable to add card: project " + project + " doesn't exist";
        }

        //controllo se l'utente ha il diritto di aggiungere una card al progetto
        Utente user = null;
        for(Utente u : registeredUsers.keySet()) {
            if(u.getUsername().equals(username)) {
                user = u;
            }
        }
        if(!progetto.getMembriProg().contains(user)) {
            return "Unable to add card: you aren't a member of project " + project;
        }

        //controllo se esiste già una card con quel nome nel progetto
        for (Card c : progetto.getListaDone()) {
            if(c.getNomeCard().equals(cardName))
                return "Unable to add card: card" + cardName + " already exists";
        }
        for (Card c : progetto.getListaToBeRevised()) {
            if(c.getNomeCard().equals(cardName))
                return "Unable to add card: card" + cardName + " already exists";
        }
        for (Card c : progetto.getListaInProgress()) {
            if(c.getNomeCard().equals(cardName))
                return "Unable to add card: card" + cardName + " already exists";
        }
        for (Card c : progetto.getListaToDo()) {
            if(c.getNomeCard().equals(cardName))
                return "Unable to add card: card" + cardName + " already exists";
        }

        //creo la nuova card e il nuovo file che conterrà lo storico
        Card newCard = new Card(cardName, description);
        progetto.aggiungiCard(newCard);
        File cardFile = new File("./" + project + "/" + cardName + ".txt");
        cardFile.createNewFile();

        //aggiungo il primo stato alla storia della card
        BufferedWriter writer = new BufferedWriter(new FileWriter("./" + project + "/" + cardName + ".txt"));
        writer.write("TODO\n");
        writer.close();

        //aggiungo la nuova card sul file delle card del progetto
        ObjectMapper objectMapper = new ObjectMapper();
        FileWriter fileWriterCards = new FileWriter("./" + project + "/" + PROJECT_CARDS_FILENAME);
        fileWriterCards.write("{\"cards\":");
        try {
            List<Card> listaCarteProgetto = new ArrayList<Card>();
            listaCarteProgetto.addAll(progetto.getListaToDo());
            listaCarteProgetto.addAll(progetto.getListaInProgress());
            listaCarteProgetto.addAll(progetto.getListaToBeRevised());
            listaCarteProgetto.addAll(progetto.getListaDone());
            objectMapper.writeValue(fileWriterCards, listaCarteProgetto);
        } catch (JsonMappingException e) {
            System.err.println("Error writing file");
            return "Unable to add card: Error writing file";
        }
        fileWriterCards.close();
        FileWriter lastCharWriter = new FileWriter(("./" + project + "/" + PROJECT_CARDS_FILENAME), true);
        lastCharWriter.append('}');
        lastCharWriter.close();

        //invio il messaggio di notifica sulla chat del progetto
        String message = "WORTH: \"" + username + " added card " + cardName + " to project " + project + "\"";
        byte[] data = message.getBytes();
        DatagramPacket dp = new DatagramPacket(data, data.length, progetto.getChatMulticastAddress(), progetto.getChatMulticastsocketNumber());
        DatagramSocket ms = new DatagramSocket();
        ms.send(dp);
        ms.close();

        return "card " + cardName + " added to project " + project;
    }

    /**
     *
     * @param projectName nome del progetto del quale si vogliono controllare le card
     * @param username username dell'utente che ha effetuato la richiesta
     * @return in caso di risposta positiva una stringa contenente la lista delle card del progetto
     *         altrimenti una risposta di errore
     */
    private String showCards(String projectName, String username) {
        //controllo che il progetto esista
        Progetto progetto = null;
        for(Progetto p : projects) {
            if(p.getNomeProg().equals(projectName)) {
                progetto = p;
                break;
            }
        }
        if(progetto == null) {
            return "Unable to show project cards: project " + projectName + " doesn't exist";
        }

        //controllo se l'utente ha il diritto di controllare le card del progetto
        Utente user = null;
        for(Utente u : registeredUsers.keySet()) {
            if(u.getUsername().equals(username)) {
                user = u;
                break;
            }
        }
        if(!progetto.getMembriProg().contains(user)) {
            return "Unable to show projects cards: you aren't a member of project " + projectName;
        }

        //raggruppo le card del progetto in base al loro stato
        List<String> toDoCardNames = new ArrayList<String>();
        List<String> inProgressCardNames = new ArrayList<String>();
        List<String> toBeRevisedCardNames = new ArrayList<String>();
        List<String> doneCardNames = new ArrayList<String>();
        for (Card c : progetto.getListaToDo()) {
            toDoCardNames.add(c.getNomeCard());
        }
        for (Card c : progetto.getListaInProgress()) {
            inProgressCardNames.add(c.getNomeCard());
        }
        for (Card c : progetto.getListaToBeRevised()) {
            toBeRevisedCardNames.add(c.getNomeCard());
        }
        for (Card c : progetto.getListaDone()) {
            doneCardNames.add(c.getNomeCard());
        }

        return "TO DO: " + toDoCardNames.toString() + "\nIN PROGRESS: " + inProgressCardNames.toString() + "\nTO BE REVISED: " + toBeRevisedCardNames.toString() + "\nDONE: " + doneCardNames.toString();
    }

    /**
     *
     * @param projectName nome del progetto al quale appartiene la card che si vuole controllare
     * @param cardName nome della carta che si vuole controllare
     * @param username username dell'utente che ha effettuato la richiesta
     * @return in caso di risposta positiva una stringa contenente le proprietà della card
     *         altrimenti una risposta di errore
     */
    private String showCard(String projectName, String cardName, String username) {
        //controllo che il progetto esista
        Progetto progetto = null;
        for(Progetto p : projects) {
            if(p.getNomeProg().equals(projectName)) {
                progetto = p;
                break;
            }
        }
        if(progetto == null) {
            return "Unable to show card " + cardName + ": project " + projectName + " doesn't exist";
        }

        //controllo se l'utente ha il diritto di controllare la card
        Utente user = null;
        for(Utente u : registeredUsers.keySet()) {
            if(u.getUsername().equals(username)) {
                user = u;
                break;
            }
        }
        if(!progetto.getMembriProg().contains(user)) {
            return "Unable to show card " + cardName + ": you aren't a member of project " + projectName;
        }

        //controllo che la card esista
        Card card = null;
        String statoCard = null;
        for (Card c : progetto.getListaToDo()) {
            if(c.getNomeCard().equals(cardName)) {
                card = c;
                statoCard = "TO DO";
            }
        }
        if(card == null) {
            for (Card c : progetto.getListaInProgress()) {
                if(c.getNomeCard().equals(cardName)) {
                    card = c;
                    statoCard = "IN PROGRESS";
                }
            }
        }
        if(card == null) {
            for (Card c : progetto.getListaToBeRevised()) {
                if(c.getNomeCard().equals(cardName)) {
                    card = c;
                    statoCard = "TO BE REVISED";
                }
            }
        }
        if(card == null) {
            for (Card c : progetto.getListaDone()) {
                if(c.getNomeCard().equals(cardName)) {
                    card = c;
                    statoCard = "DONE";
                }
            }
        }

        if(card == null) {
            return "Unable to show card " + cardName + ": the card doesn't exist";
        }
        return "name: " + card.getNomeCard() + "\nstate: " + statoCard + "\ndescription: " + card.getDescrizione();
    }

    /**
     *
     * @param projectName nome del progetto al quale appartiene la card da spostare
     * @param cardName nome della card da spostare
     * @param srcList stato di partenza della card
     * @param desList stato della card dopo lo spostamento
     * @param username nome dell'utente che ha effettuato la richiesta
     * @return risposta del server per segnalare il successo o meno dell'operazione
     * @throws IOException se si verificano errori nella scrittura del file
     */
    private String moveCard(String projectName, String cardName, String srcList, String desList, String username) throws IOException {
        //controllo che il progetto esista
        Progetto progetto = null;
        for(Progetto p : projects) {
            if(p.getNomeProg().equals(projectName)) {
                progetto = p;
                break;
            }
        }
        if(progetto == null) {
            return "Unable to move card " + cardName + ": project " + projectName + " doesn't exist";
        }

        //controllo se l'utente ha il diritto di spostare la card
        Utente user = null;
        for(Utente u : registeredUsers.keySet()) {
            if(u.getUsername().equals(username)) {
                user = u;
                break;
            }
        }
        if(!progetto.getMembriProg().contains(user)) {
            return "Unable to move card " + cardName + ": you aren't a member of project " + projectName;
        }

        //controllo se il valore dello stato di partenza è valido
        if(srcList.toUpperCase(Locale.ROOT).equals("TODO") || srcList.toUpperCase(Locale.ROOT).equals("INPROGRESS") || srcList.toUpperCase(Locale.ROOT).equals("TOBEREVISED") || srcList.toUpperCase(Locale.ROOT).equals("DONE")) {
            srcList = srcList.toUpperCase(Locale.ROOT);
        } else {
            return "Unable to move card " + cardName + ": source list doesn't exist";
        }

        //controllo se il valore dello stato di arrivo è valido
        if(desList.toUpperCase(Locale.ROOT).equals("TODO") || desList.toUpperCase(Locale.ROOT).equals("INPROGRESS") || desList.toUpperCase(Locale.ROOT).equals("TOBEREVISED") || desList.toUpperCase(Locale.ROOT).equals("DONE")) {
            desList = desList.toUpperCase(Locale.ROOT);
        } else {
            return "Unable to move card " + cardName + ": destination list doesn't exist";
        }

        //controllo se la card esiste e in caso effettuo lo spostamento
        if(!progetto.spostaCarta(cardName, srcList, desList)) {
            return "Unable to move card " + cardName + ": chosen card doesn't exist in list " + srcList;
        }

        //aggiorno il file con lo storico della card
        BufferedWriter writer = new BufferedWriter(new FileWriter(("./" + projectName + "/" + cardName + ".txt"), true));
        writer.write(desList + "\n");
        writer.close();

        //invio il messaggio di notifica sulla chat del progetto
        String message = "WORTH: \"" + username + " moved card " + cardName + " from " + srcList + " to " + desList + " in project " + projectName + "\"";
        byte[] data = message.getBytes();
        DatagramPacket dp = new DatagramPacket(data, data.length, progetto.getChatMulticastAddress(), progetto.getChatMulticastsocketNumber());
        DatagramSocket ms = new DatagramSocket();
        ms.send(dp);
        ms.close();

        return "OK";
    }

    /**
     *
     * @param projectName nome del progetto al quale appartiene la card
     * @param cardName nome della card di cui si vuole sapere la storia
     * @param username username dell'utente che ha effettuato la richiesta
     * @return in caso di risposta positiva una stringa contenente la storia della card
     *         altrimenti una risposta di errore
     * @throws IOException se si verificano errori nella lettura del file
     */
    private String cardHistory(String projectName, String cardName, String username) throws IOException {
        //controllo che il progetto esista
        Progetto progetto = null;
        for(Progetto p : projects) {
            if(p.getNomeProg().equals(projectName)) {
                progetto = p;
                break;
            }
        }
        if(progetto == null) {
            return "Unable to get card history: project " + projectName + " doesn't exist";
        }

        //controllo se l'utente ha il diritto di sapere la storia della card
        Utente user = null;
        for(Utente u : registeredUsers.keySet()) {
            if(u.getUsername().equals(username)) {
                user = u;
                break;
            }
        }
        if(!progetto.getMembriProg().contains(user)) {
            return "Unable to get card history: you aren't a member of project " + projectName;
        }

        //controllo che la card esista
        Card card = null;
        for (Card c : progetto.getListaToDo()) {
            if(c.getNomeCard().equals(cardName)) {
                card = c;
            }
        }
        if(card == null) {
            for (Card c : progetto.getListaInProgress()) {
                if(c.getNomeCard().equals(cardName)) {
                    card = c;
                }
            }
        }
        if(card == null) {
            for (Card c : progetto.getListaToBeRevised()) {
                if(c.getNomeCard().equals(cardName)) {
                    card = c;
                }
            }
        }
        if(card == null) {
            for (Card c : progetto.getListaDone()) {
                if(c.getNomeCard().equals(cardName)) {
                    card = c;
                }
            }
        }
        if(card == null) {
            return "Unable to show card " + cardName + " history: the card doesn't exist";
        }

        //leggo dal file tutti gli stati in cui è stata la card
        List<String> history = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new FileReader("./" + projectName + "/" + cardName + ".txt"));
        String line = reader.readLine();
        while(line != null) {
            history.add(line);
            line = reader.readLine();
        }
        reader.close();

        //costruisco la risposta
        String result = "card " + cardName + " history:\n";
        for(String s : history) {
            switch (s) {
                case "TODO":
                    result = result.concat("TO DO\n");
                    break;
                case "INPROGRESS":
                    result = result.concat("IN PROGRESS\n");
                    break;
                case "TOBEREVISED":
                    result = result.concat("TO BE REVISED\n");
                    break;
                case "DONE":
                    result = result.concat("DONE\n");
                    break;
            }
        }
        return result;
    }

    /**
     *
     * @param projectName nome del progetto da cancellare
     * @param username nome dell'utente che ha effettuato la richiesta
     * @return in caso di risposta positiva una stringa contenente la storia della card
     *         altrimenti una risposta di errore
     * @throws IOException se si verificano errori nella rimozione dei file
     */
    private String cancelProject(String projectName, String username) throws IOException {
        //controllo che il progetto esista
        Progetto progetto = null;
        for(Progetto p : projects) {
            if(p.getNomeProg().equals(projectName)) {
                progetto = p;
                break;
            }
        }
        if(progetto == null) {
            return "Unable to delete project: project " + projectName + " doesn't exist";
        }

        //controllo se l'utente ha il diritto di cancellare il progetto
        Utente user = null;
        for(Utente u : registeredUsers.keySet()) {
            if(u.getUsername().equals(username)) {
                user = u;
                break;
            }
        }
        if(!progetto.getMembriProg().contains(user)) {
            return "Unable to delete project: you aren't a member of project " + projectName;
        }

        //controllo se tutte le task del progetto sono state ultimate
        if(progetto.getListaToDo().size() > 0 || progetto.getListaInProgress().size() > 0 || progetto.getListaToBeRevised().size() > 0) {
            return "Unable to delete project: some tasks are not ultimated";
        }

        List<Utente> utenti = new ArrayList<>(progetto.getMembriProg());
        String nomeProgetto = progetto.getNomeProg();

        //rimuovo il progetto, la sua directory e tutti i file contenuti in essa
        projects.remove(progetto);
        File projDir = new File("./" + progetto.getNomeProg());
        File[] projFiles = projDir.listFiles();
        if(projFiles != null) {
            for(File f : projFiles) {
                Files.delete(f.toPath());
            }
        }
        multicastAddressInUse.remove(progetto.getChatMulticastAddress().getHostAddress());
        Files.delete(projDir.toPath());
        updateCancelledProject(utenti, nomeProgetto); //comunico al client di terminare il thread che riceve i messaggi della chat di progetto

        return "project " + projectName + " successfully deleted";
    }

    /**
     *
     * @param username nome dell'utente che ha effettuato il login o il logout
     * @param state true ==> online, false ==> offline
     * @throws RemoteException se si verificano errori durante l'esecuzione della chiamata remota
     */
    public void updateLog(String username, boolean state) throws RemoteException {
        doCallback(username, state, false);
    }

    /**
     *
     * @param username nome dell'utente che si è registrato
     * @throws RemoteException se si verificano errori durante l'esecuzione della chiamata remota
     */
    public void updateRegistration(String username) throws RemoteException {
        doCallback(username, false, true);
    }

    /**
     *
     * @param username nome dell'utente che ha effettuato un operazione di log o si è registrato
     * @param state true ==> online, false ==> offline
     * @param registrated true ==> l'utente si è registrato, false ==> l'utente ha effettuato un login o un logout
     * @throws RemoteException se si verificano errori durante l'esecuzione della chiamata remota
     */
    private void doCallback(String username, boolean state, boolean registrated) throws RemoteException {
        synchronized (clients) {
            Iterator i = clients.iterator();
            NotifyEventInterface clientToRemove = null;
            while (i.hasNext()) {
                NotifyEventInterface client = (NotifyEventInterface) i.next();
                try {
                    client.notifyUser(username, state, registrated);
                } catch (ConnectException e) {
                    //generata se precedentemente c'è stato il crash di un utente
                    //che è terminato senza disiscriversi dalla callback
                    clientToRemove = client;
                }
            }
            clients.remove(clientToRemove);
        }
    }

    /**
     *
     * @param user utente che ha creato o che è stato aggiunto ad un progetto
     * @param p progetto al quale è stato aggiunto l'utente
     * @throws RemoteException se si verificano errori durante l'esecuzione della chiamata remota
     */
    public void updateNewProject(Utente user, Progetto p) throws RemoteException {
        projectNewMemberCallback(user, p);
    }

    /**
     *
     * @param user utente che ha creato o che è stato aggiunto ad un progetto
     * @param p progetto al quale è stato aggiunto l'utente
     * @throws RemoteException se si verificano errori durante l'esecuzione della chiamata remota
     */
    private void projectNewMemberCallback(Utente user, Progetto p) throws RemoteException {
        synchronized (clients) {
            //notifico al client loggato su quel utente di attivare un thread per la ricezione dei messaggi della chat di progetto
            Iterator i = clients.iterator();
            while (i.hasNext()) {
                NotifyEventInterface client = (NotifyEventInterface) i.next();
                if (client.getUserLogged().equals(user.getUsername())) {
                    client.notifyNewProject(p);
                }
            }
        }
    }

    /**
     *
     * @param list lista degli utenti che facevano parte del progetto
     * @param projectName nome del progetto appena cancellato
     * @throws RemoteException se si verificano errori durante l'esecuzione della chiamata remota
     */
    public void updateCancelledProject(List<Utente> list, String projectName) throws RemoteException {
        cancelledProjectCallback(list, projectName);
    }

    /**
     *
     * @param list lista degli utenti che facevano parte del progetto
     * @param projectName nome del progetto appena cancellato
     * @throws RemoteException se si verificano errori durante l'esecuzione della chiamata remota
     */
    private void cancelledProjectCallback(List<Utente> list, String projectName) throws RemoteException {
        //notifico a tutti i client loggati con gli utenti nella lista di terminare il thread dedicato alla ricezione dei messaggi della chat del progetto eliminato
        List<String> usernames = new ArrayList<>();
        for (Utente u : list) {
            usernames.add(u.getUsername());
        }
        synchronized (clients) {
            Iterator i = clients.iterator();
            while (i.hasNext()) {
                NotifyEventInterface client = (NotifyEventInterface) i.next();
                if (usernames.contains(client.getUserLogged())) {
                    client.notifyProjectCancelled(projectName);
                }
            }
        }
    }

    @Override
    public synchronized String register(String username, String password) throws RemoteException {
        File usersFile = new File(USERS_FILENAME);
        Utente u = null;
        try {
            if(!usersFile.exists()) {
                usersFile.createNewFile();
            }

            //controllo che l'username non sia già stato scelto da qualche altro utente
            for(Utente user : registeredUsers.keySet()) {
                if(user.getUsername().equals(username)) {
                    return "Unable to register: Username already used";
                }
            }

            //creo il nuovo utente
            ObjectMapper objectMapper = new ObjectMapper();
            u = new Utente(username, password);
            registeredUsers.put(u, false);

            //aggiorno il file degli utenti
            BufferedWriter writer = new BufferedWriter(new FileWriter(usersFile));
            writer.write("{\"utenti\":");
            try {
                List<Utente> listaUtentiRegistrati = new ArrayList<Utente>(registeredUsers.keySet());
                objectMapper.writeValue(writer, listaUtentiRegistrati);
            } catch (JsonMappingException e) {
                System.err.println("Error writing file");
                return "Unable to register: Error writing file";
            }
            writer.close();
            BufferedWriter lastCharWriter = new BufferedWriter(new FileWriter(usersFile, true));
            lastCharWriter.append('}');
            lastCharWriter.close();

            updateRegistration(username); //notifico a tutti gli utenti la nuova registrazione
        } catch (IOException e) {
            e.printStackTrace();
            return ("Unable to register: " + e.getMessage());
        }
        return "OK";
    }

    @Override
    public Map<String, Boolean> registerForCallbackUsers(NotifyEventInterface client) throws RemoteException {
        synchronized (clients) {
            if (!clients.contains(client)) {
                clients.add(client);
            }
        }

        synchronized (registeredUsers) {
            Map<String, Boolean> returnMap = new HashMap<String, Boolean>();
            for (Utente u : registeredUsers.keySet()) {
                returnMap.put(u.getUsername(), registeredUsers.get(u));
            }
            return returnMap;
        }
    }

    @Override
    public List<Progetto> registerForCallbackProjects(NotifyEventInterface client) throws RemoteException {
        synchronized (clients) {
            if (!clients.contains(client)) {
                clients.add(client);
            }
        }

        List<Progetto> returnList = new ArrayList<Progetto>();
        Utente user = null;
        synchronized (registeredUsers) {
            for (Utente u : registeredUsers.keySet()) {
                if (client.getUserLogged().equals(u.getUsername())) {
                    user = u;
                }
            }
        }

        synchronized (projects) {
            for (Progetto p : projects) {
                if (p.getMembriProg().contains(user)) {
                    returnList.add(p);
                }
            }
        }

        return returnList;
    }

    @Override
    public void unregisterForCallbacks(NotifyEventInterface client) throws RemoteException {
        synchronized (clients) {
            clients.remove(client);
        }
    }

    /**
     *
     * @return numero del primo indirizzo di multicast disponibile (non usato dalle altre chat di progetto)
     *         o un messaggio di errore
     */
    private String getNextAvailableAddress() {
        String address = "224.0.1.0";
        while(multicastAddressInUse.contains(address)) {
            address = incrementIP(address);
        }

        return address;
    }

    /**
     *
     * @param address indirizzo IP multicast da incrementare
     * @return indirizzo IP multicast incrementato
     *         o un messaggio di errore
     */
    private String incrementIP(String address) {
        //traduzione in  interi
        String[] splittedAddress = address.split("\\.");
        if(splittedAddress.length != 4) {
            return "error";
        }
        int[] ottetti = new int[4];
        for(int i = 0 ; i < splittedAddress.length ; i++) {
            ottetti[i] = Integer.parseInt(splittedAddress[i]);
        }

        //incremento
        ottetti[3]++;
        if(ottetti[3] == 256) {
            ottetti[3] = 0;
            ottetti[2]++;
            if(ottetti[2] == 256) {
                ottetti[2] = 0;
                ottetti[1]++;
                if(ottetti[1] == 256) {
                    ottetti[1] = 0;
                    ottetti[0]++;
                }
            }
        }

        if(ottetti[0] == 240) {
            return "finished";
        }
        return ottetti[0] + "." + ottetti[1] + "." + ottetti[2] + "." + ottetti[3];
    }
}
