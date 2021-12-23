package client;

import java.rmi.*;

public interface NotifyEventInterface extends Remote {

    /**
     *
     * @param username nome dell'utente che si è appena registrato o loggato
     * @param state segnala se l'utente è online o offline
     * @param reg segnala se la notifica è arrivata a seguito di una registrazione o di un'operazione di log
     * @throws RemoteException se si verificano errori durante l'esecuzione della chiamata remota
     */
    public void notifyUser(String username, boolean state, boolean reg) throws RemoteException;

    /**
     *
     * @param p nuovo progetto creato
     * @throws RemoteException se si verificano errori durante l'esecuzione della chiamata remota
     */
    public void notifyNewProject(Progetto p) throws RemoteException;

    /**
     *
     * @param projectName nome del progetto appena cancellato
     * @throws RemoteException se si verificano errori durante l'esecuzione della chiamata remota
     */
    public void notifyProjectCancelled(String projectName) throws RemoteException;

    /**
     *
     * @return l'username dell'utente loggato nella sessione
     * @throws RemoteException se si verificano errori durante l'esecuzione della chiamata remota
     */
    public String getUserLogged() throws RemoteException;
}