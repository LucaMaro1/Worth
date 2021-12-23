package client;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.rmi.*;
import java.rmi.registry.*;
import java.rmi.server.*;
import java.util.*;

public class Client extends RemoteObject implements NotifyEventInterface {
    private final static int readbufflen = 4096;
    private final static int writebufflen = 3072;

    private final InetAddress serverAddress;
    private final int serverRmiPort;
    private final int serverTcpPort;
    private Map<String, Boolean> mappaStatoUtenti; //contiene la lista degli utenti registrati e il loro stato
    private String userLogged; //username dell'utente loggato in un certo momento

    private final List<MessageReader> chatReaders; //lista di MessageReader che leggono la chat, uno per progetto
    private final List<Thread> threadReaders; //thread che eseguono le task dei MessageReader

    public Client(InetAddress serverAddress, int serverRmiPort, int serverTcpPort) {
        super();
        this.serverAddress = serverAddress;
        this.serverRmiPort = serverRmiPort;
        this.serverTcpPort = serverTcpPort;
        this.mappaStatoUtenti = new HashMap<String, Boolean>();
        this.userLogged = ""; //quando nessuno è loggato viene settato a stringa vuota

        this.chatReaders = new ArrayList<MessageReader>();
        this.threadReaders = new ArrayList<Thread>();
    }

    public void start() throws InterruptedException, NotBoundException, IOException {
        Registry registry = LocateRegistry.getRegistry(serverAddress.getHostAddress(), serverRmiPort); //ottiene un riferimento per il registro
        ServerInterface server = (ServerInterface) registry.lookup("SERVER"); //ottiene un riferimento al server
        Scanner scanner = new Scanner(System.in);

        NotifyEventInterface stub = (NotifyEventInterface) UnicastRemoteObject.exportObject(this, 0);

        //instaura connessione
        SocketAddress address = new InetSocketAddress(this.serverAddress, this.serverTcpPort);
        SocketChannel clientSocketChannel = SocketChannel.open(address);
        clientSocketChannel.configureBlocking(true);

        boolean logged = false;
        boolean quit = false; //se settata a true il client termina
        String command = "";
        while(!quit) {
            command = scanner.nextLine(); //acquisisco il comando

            String[] splitCommand = command.split(" "); //suddivido l'input nel comando vero e proprio e nei suoi parametri
            boolean consentito = true;
            switch (splitCommand[0]) {
                case "help":
                    if(splitCommand.length == 1) {
                        help();
                    } else {
                        System.out.println("Syntax error");
                        System.out.println("Usage: help");
                    }
                    break;
                case "register":
                    if(!logged) {
                        if(splitCommand.length == 3) {
                            if((splitCommand[1].length() + splitCommand[2].length() + 2 + "register".length()) > writebufflen) { //evita buffer overflow
                                System.out.println("Unable to register: username or password too long, try to reduce their length\n");
                            } else {
                                String res = server.register(splitCommand[1], splitCommand[2]);
                                System.out.println(res + "\n");
                            }
                        }
                        else {
                            System.out.println("Unable to register: syntax error\n");
                        }
                    } else {
                        System.out.println("Unable to register: logout before register another user\n");
                    }
                    break;
                case "login":
                    if(logged) {
                        consentito = false;
                        System.out.println("Unable to login: there's a user already logged in\n");
                    }

                    if(consentito) {
                        String result = communicateWithServer(command, clientSocketChannel);
                        System.out.println(result + "\n");

                        synchronized (this) {
                            if (splitCommand.length == 3 && result.equalsIgnoreCase(splitCommand[1] + " logged in")) { //in caso di risposta positiva del server
                                logged = true;
                                userLogged = splitCommand[1];
                                mappaStatoUtenti = server.registerForCallbackUsers(stub); //realizzata nella modalità alternativa pervista dalle specifiche
                                List<Progetto> projects = server.registerForCallbackProjects(stub);
                                for (Progetto p : projects) {
                                    //partono i thread che ascoltano e salvano i messaggi che arrivano in chat
                                    MessageReader newMessageReader = new MessageReader(p);
                                    chatReaders.add(newMessageReader);
                                    Thread messageReaderThread = new Thread(newMessageReader);
                                    threadReaders.add(messageReaderThread);
                                    messageReaderThread.start();
                                }

                                printUsers();
                            }
                        }
                    }
                    break;
                case "logout":
                    if(!logged) {
                        consentito = false;
                        System.out.println("Unable to logout: there isn't a user logged yet\n");
                    }

                    if(consentito) {
                        String result = communicateWithServer((command + " " + userLogged), clientSocketChannel);
                        System.out.println(result + "\n");

                        synchronized (this) {
                            if (result.equalsIgnoreCase(userLogged + " logged out")) { //in caso di risposta positiva del server
                                //riporto tutte le variabili allo stato iniziale
                                logged = false;
                                userLogged = "";
                                server.unregisterForCallbacks(stub);
                                mappaStatoUtenti.clear();


                                for (int i = 0; i < chatReaders.size(); i++) {
                                    chatReaders.get(i).stop();
                                    threadReaders.get(i).join();
                                }
                                chatReaders.clear();
                                threadReaders.clear();
                            }
                        }
                    }
                    break;
                //users e online_users vengono eseguite in locale
                case "users":
                    if(logged)
                        printUsers();
                    else
                        System.out.println("Unable to execute operation: try to login before\n");
                    break;
                case "online_users":
                    if(logged)
                        printOnlineUsers();
                    else
                        System.out.println("Unable to execute operation: try to login before\n");
                    break;
                //le seguenti hanno bisogno di comunicare con il server
                //oltre all'input digitato dall'utente, si comunica al server il nome dell'utente loggato
                //per dei controlli da effettuare prima di eseguire l'operazione
                case "create_project":
                case "list_projects":
                case "add_member":
                case "show_members":
                case "add_card":
                case "show_cards":
                case "show_card":
                case "move_card":
                case "card_history":
                case "cancel_project":
                    if(logged) {
                        String result = communicateWithServer((command + " " + userLogged), clientSocketChannel);
                        System.out.println(result + "\n");
                    } else {
                        System.out.println("Unable to execute operation: try to login before\n");
                    }
                    break;
                //le operazioni riguardanti la chat vengono eseguite interfacciandosi con i MessageReader
                case "read_chat":
                    if(logged) {
                        if(splitCommand.length == 2) {
                            boolean contenuto = false;
                            synchronized (chatReaders) {
                                for (MessageReader mr : chatReaders) {
                                    //check nome del progetto corretto
                                    if (mr.getProgetto().getNomeProg().equals(splitCommand[1])) {
                                        contenuto = true;
                                        //stampa dei messaggi
                                        List<String> messages = mr.getMessaggi();
                                        if (messages != null) {
                                            for (String messaggio : messages) {
                                                System.out.println(messaggio);
                                            }
                                        }
                                        System.out.println("|------- END CHAT -------|\n");
                                    }
                                }
                            }
                            if(!contenuto) {
                                System.out.println("Unable to read chat: you aren't a member of project " + splitCommand[1] + "\n");
                            }
                        } else {
                            System.out.println("Unable to read chat: syntax error\n");
                        }
                    } else {
                        System.out.println("Unable to execute operation: try to login before\n");
                    }
                    break;
                case "send_msg":
                    if(logged) {
                        if(splitCommand.length == 2) {
                            boolean contenuto = false;
                            synchronized (chatReaders) {
                                for (MessageReader mr : chatReaders) {
                                    //check nome del progetto corretto
                                    if (mr.getProgetto().getNomeProg().equals(splitCommand[1])) {
                                        contenuto = true;
                                        //costruzione del messaggio
                                        System.out.print("write your message: ");
                                        String message = scanner.nextLine();
                                        message = userLogged + ": \"" + message + "\""; //aggiungo un header al messaggio per indicare l'utente che lo ha mandato

                                        //invio del messaggio alla chat (gruppo multicast)
                                        byte[] data = message.getBytes();
                                        DatagramPacket dp = new DatagramPacket(data, data.length, mr.getProgetto().getChatMulticastAddress(), mr.getProgetto().getChatMulticastsocketNumber());
                                        DatagramSocket ms = new DatagramSocket();
                                        ms.send(dp);
                                        ms.close();
                                    }
                                }
                            }
                            if(!contenuto) {
                                System.out.println("Unable to send message: you aren't a member of project " + splitCommand[1] + "\n");
                            } else {
                                //tutto ok => segnalo corretto invio del messaggio
                                System.out.println("OK\n");
                            }
                        } else {
                            System.out.println("Unable to send message: syntax error\n");
                        }
                    } else {
                        System.out.println("Unable to execute operation: try to login before\n");
                    }
                    break;
                case "quit": //per chiusura applicazione
                    if(logged) {
                        consentito = false;
                        System.out.println("logout before quit\n");
                    }

                    if(consentito) {
                        String result = communicateWithServer(command, clientSocketChannel);
                        System.out.println(result + "\n");

                        //chiudo la connessione col server
                        if(result.equalsIgnoreCase("OK")) {
                            clientSocketChannel.close();
                            quit = true;
                        }
                    }
                    break;
                default:
                    System.out.println("unrecognized command\n");
            }
        }
    }

    /**
     *
     * @param command input contenente il comando da eseguire
     * @param clientSocketChannel socket con la quale comunicare col server
     * @return la risposta del server
     * @throws IOException se ci sono errori nell comunicazione col server
     */
    private String communicateWithServer(String command, SocketChannel clientSocketChannel) throws IOException {
        ByteBuffer writeBuffer = ByteBuffer.allocate(writebufflen);
        ByteBuffer readBuffer = ByteBuffer.allocate(readbufflen);

        //fase scrittura sul canale
        if(command.length() > writebufflen) {
            command = command.substring(0, writebufflen);
        }
        writeBuffer.put(command.getBytes());

        writeBuffer.flip();
        clientSocketChannel.write(writeBuffer);

        //fase lettura dal canale
        clientSocketChannel.read(readBuffer);
        readBuffer.flip();
        String result = new String(readBuffer.array(), StandardCharsets.UTF_8);

        if(result.indexOf(0) >= 0) {
            result = result.substring(0, result.indexOf(0));
        }
        return result;
    }

    /**
     *  stampa gli utenti registrati
     */
    private synchronized void printUsers() {
        System.out.println("Utenti registrati:");
        for (String username : mappaStatoUtenti.keySet()) {
            System.out.print(username);
            if(mappaStatoUtenti.get(username))
                System.out.println(" online");
            else
                System.out.println(" offline");
        }
        System.out.println();
    }

    /**
     * stampa gli utenti online
     */
    private synchronized void printOnlineUsers() {
        System.out.println("Utenti online:");
        for (String username : mappaStatoUtenti.keySet()) {
            if(mappaStatoUtenti.get(username)) {
                System.out.println(username + " online");
            }
        }
        System.out.println();
    }

    /**
     * stampa il messaggio di aiuto contenente la sintassi di ogni comando
     */
    private void help() {
        System.out.println("Commands:");
        System.out.println("register [username] [password]");
        System.out.println("login [username] [password]");
        System.out.println("logout");
        System.out.println("users");
        System.out.println("online_users");
        System.out.println("create_project [project_name]");
        System.out.println("list_projects");
        System.out.println("add_member [project_name] [new_member_username]");
        System.out.println("show_members [project_name]");
        System.out.println("add_card [project_name] [card_name] [card_description]");
        System.out.println("show_cards [project_name]");
        System.out.println("show_card [project_name] [card_name]");
        System.out.println("move_card [project_name] [card_name] [src_state] [dest_state]");
        System.out.println("card_history [project_name] [card_name]");
        System.out.println("cancel_project [project_name]");
        System.out.println("read_chat [project_name]");
        System.out.println("send_msg [project_name]");
        System.out.println("quit\n");
    }

    @Override
    public synchronized void notifyUser(String username, boolean state, boolean reg) throws RemoteException {
        if(state) { //utente ha effettuato il login
            System.out.println("\nUPDATE: " + username + " " + "online\n");
        }
        else { //notifica utente offline
            if(reg) { //utente offline perché si è appena registrato
                System.out.println("\nUPDATE: new user: " + username + "\n");
            } else { //utente offline perché ha eseguito il logout
                System.out.println("\nUPDATE: " + username + " " + "offline\n");
            }
        }
        mappaStatoUtenti.put(username, state); //aggiorno lista e stato degli utenti
    }

    @Override
    public void notifyNewProject(Progetto p) throws RemoteException {
        synchronized (chatReaders) {
            //creo un nuovo task e un nuovo thread che salvi i messaggi della chat del nuovo progetto
            MessageReader newMessageReader = new MessageReader(p);
            chatReaders.add(newMessageReader);
            Thread messageReaderThread = new Thread(newMessageReader);
            threadReaders.add(messageReaderThread);
            messageReaderThread.start();
        }
    }

    @Override
    public void notifyProjectCancelled(String projectName) throws RemoteException {
        synchronized (chatReaders) {
            for (int i = 0; i < chatReaders.size(); i++) {
                if (chatReaders.get(i).getProgetto().getNomeProg().equals(projectName)) {
                    //faccio terminare il thread delegato per una chat di progetto
                    chatReaders.get(i).stop();
                    try {
                        threadReaders.get(i).join();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    //rimuovo dalle liste il task e il thread delegati per la chat del progetto cancellato
                    chatReaders.remove(i);
                    threadReaders.remove(i);
                }
            }
        }
    }

    @Override
    public String getUserLogged() {
        return userLogged;
    }
}
