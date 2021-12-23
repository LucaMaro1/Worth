package client;

import java.io.Serializable;

public class Utente implements Serializable {
    private final String username;
    private final String password;

    public Utente(String username, String password) {
        this.username = username;
        this.password = password;
    }

    /**
     *
     * @return username dell'utente
     */
    public String getUsername() {
        return username;
    }

    /**
     *
     * @return password dell'utente
     */
    public String getPassword() {
        return password;
    }
}
