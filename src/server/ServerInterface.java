package server;

import java.rmi.*;
import java.util.List;
import java.util.Map;

public interface ServerInterface extends Remote {
    /**
     *
     * @param username username del nuovo utente da registrare
     * @param password password associata al nuovo utente da registrare
     * @return risposta del server
     * @throws RemoteException se si verificano errori durante l'esecuzione della chiamata remota
     */
    public String register(String username, String password) throws RemoteException;

    /**
     *
     * @param client stub riferito al client che richiede di iscriversi alla callback
     * @return lista degli utenti registrati e del loro stato (online/offline)
     * @throws RemoteException se si verificano errori durante l'esecuzione della chiamata remota
     */
    public Map<String, Boolean> registerForCallbackUsers(NotifyEventInterface client) throws RemoteException;

    /**
     *
     * @param client stub riferito al client che richiede di iscriversi alla callback
     * @return lista dei progetti di cui l'utente è membro
     * @throws RemoteException se si verificano errori durante l'esecuzione della chiamata remota
     */
    public List<Progetto> registerForCallbackProjects(NotifyEventInterface client) throws RemoteException;

    /**
     *
     * @param client stub riferito al client che richiede di disiscriversi dalla callback
     * @throws RemoteException se si verificano errori durante l'esecuzione della chiamata remota
     */
    public void unregisterForCallbacks(NotifyEventInterface client) throws RemoteException;
}
