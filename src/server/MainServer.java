package server;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.io.IOException;

public class MainServer {
    private static final int RMI_PORT = 10000;
    private static final int TCP_PORT = 15000;

    public static void main(String[] args) {
        Server server = new Server(TCP_PORT);
        try {
            //registrazione presso il registry
            ServerInterface stub = (ServerInterface) UnicastRemoteObject.exportObject (server,0);
            LocateRegistry.createRegistry(RMI_PORT);
            Registry registry = LocateRegistry.getRegistry(RMI_PORT);
            registry.rebind("SERVER", stub);

            server.start();
        } catch (RemoteException e) {
            System.out.println("Remote exception " + e.getMessage());
        } catch (IOException ioe) {
            System.out.println("Exception " + ioe.getMessage());
        }
    }
}
