package client;

import java.io.Serializable;
import java.net.InetAddress;
import java.nio.channels.UnsupportedAddressTypeException;
import java.util.ArrayList;
import java.util.List;

public class Progetto implements Serializable {
    private final String nomeProg;
    private final List<Card> listaToDo;
    private final List<Card> listaInProgress;
    private final List<Card> listaToBeRevised;
    private final List<Card> listaDone;
    private final List<Utente> membriProg;
    //per implementazione chat
    private final InetAddress chatMulticastAddress;
    private final int chatMulticastsocketNumber;

    public Progetto(String nomeProg, Utente firstMember, InetAddress chatMulticastAddress, int chatMulticastsocketNumber) throws UnsupportedAddressTypeException {
        this.nomeProg = nomeProg;
        this.listaToDo = new ArrayList<Card>();
        this.listaInProgress = new ArrayList<Card>();
        this.listaToBeRevised = new ArrayList<Card>();
        this.listaDone = new ArrayList<Card>();
        this.membriProg = new ArrayList<Utente>();
        this.chatMulticastsocketNumber = chatMulticastsocketNumber;
        if(chatMulticastAddress.isMulticastAddress())
            this.chatMulticastAddress = chatMulticastAddress;
        else
            throw new UnsupportedAddressTypeException();

        //firstMember è l'utente che ha creato il progetto
        this.membriProg.add(firstMember); //aggiungo subito un membro al progetto affinché ce ne si sempre almeno uno
    }

    /**
     *
     * @return nome del progetto
     */
    public String getNomeProg() {
        return nomeProg;
    }

    /**
     *
     * @return lista dei membri del progetto
     */
    public List<Utente> getMembriProg() {
        return membriProg;
    }

    /**
     *
     * @param u utente da aggiungere alla lista dei membri del progetto
     */
    public void aggiungiMembro(Utente u) {
        membriProg.add(u);
    }

    /**
     *
     * @param users lista di utenti da aggiungere come mebri del progetto
     */
    public void aggiungiMembri(List<Utente> users) {
        membriProg.addAll(users);
    }

    /**
     *
     * @return cards in stato DONE
     */
    public List<Card> getListaDone() {
        return listaDone;
    }

    /**
     *
     * @return cards in stato TO BE REVISED
     */
    public List<Card> getListaToBeRevised() {
        return listaToBeRevised;
    }

    /**
     *
     * @return cards in stato IN PROGRESS
     */
    public List<Card> getListaInProgress() {
        return listaInProgress;
    }

    /**
     *
     * @return cards in stato TO DO
     */
    public List<Card> getListaToDo() {
        return listaToDo;
    }

    /**
     *
     * @return indirizzo multicast del gruppo della chat di progetto
     */
    public InetAddress getChatMulticastAddress() {
        return chatMulticastAddress;
    }

    /**
     *
     * @return numero di porta del gruppo della chat di progetto
     */
    public int getChatMulticastsocketNumber() {
        return chatMulticastsocketNumber;
    }

    /**
     *
     * @param c card da aggiungere al progetto
     */
    public void aggiungiCard(Card c) {
        listaToDo.add(c);
    }

    /**
     *
     * @param cardName nome della carta a cui cambiare stato
     * @param src lista di provenienza
     * @param des lista di destinazione
     * @return true se lo spostamento è avvenuto correttamente
     *         false se lo spostamento non è avvenuto
     */
    public boolean spostaCarta(String cardName, String src, String des) {
        boolean success = false;
        Card carta = null;
        switch (src) {
            case "TODO":
                //cerco la carta
                for(int i = 0 ; i < listaToDo.size() ; i++) {
                    if(listaToDo.get(i).getNomeCard().equals(cardName)) {
                        carta = listaToDo.remove(i);
                    }
                }
                if(carta != null) { //se l'ho trovata la sposto
                    aggiungiCard(carta, des);
                    success = true;
                }
                break;
            case "INPROGRESS":
                //cerco la carta
                for(int i = 0 ; i < listaInProgress.size() ; i++) {
                    if(listaInProgress.get(i).getNomeCard().equals(cardName)) {
                        carta = listaInProgress.remove(i);
                    }
                }
                if(carta != null) { //se l'ho trovata la sposto
                    aggiungiCard(carta, des);
                    success = true;
                }
                break;
            case "TOBEREVISED":
                //cerco la carta
                for(int i = 0 ; i < listaToBeRevised.size() ; i++) {
                    if(listaToBeRevised.get(i).getNomeCard().equals(cardName)) {
                        carta = listaToBeRevised.remove(i);
                    }
                }
                if(carta != null) { //se l'ho trovata la sposto
                    aggiungiCard(carta, des);
                    success = true;
                }
                break;
            case "DONE":
                //cerco la carta
                for(int i = 0 ; i < listaDone.size() ; i++) {
                    if(listaDone.get(i).getNomeCard().equals(cardName)) {
                        carta = listaDone.remove(i);
                    }
                }
                if(carta != null) { //se l'ho trovata la sposto
                    aggiungiCard(carta, des);
                    success = true;
                }
                break;
        }
        return success;
    }

    /**
     *
     * @param c card da aggiungere
     * @param des lista alla quale aggiungere la card
     */
    private void aggiungiCard(Card c, String des) {
        switch (des) {
            case "TODO":
                listaToDo.add(c);
                break;
            case "INPROGRESS":
                listaInProgress.add(c);
                break;
            case "TOBEREVISED":
                listaToBeRevised.add(c);
                break;
            case "DONE":
                listaDone.add(c);
                break;
        }
    }
}
