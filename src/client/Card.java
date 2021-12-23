package client;

import java.io.Serializable;

public class Card implements Serializable {
    private final String nomeCard;
    private final String descrizione;


    public Card(String nomeCard, String descrizione) {
        this.nomeCard = nomeCard;
        this.descrizione = descrizione;
    }

    /**
     *
     * @return nome della card
     */
    public String getNomeCard() {
        return nomeCard;
    }

    /**
     *
     * @return descrizione della card
     */
    public String getDescrizione() {
        return descrizione;
    }
}
