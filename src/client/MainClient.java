package client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.rmi.*;

public class MainClient {
    private static final int SERVER_RMI_PORT = 10000;
    private static final int SERVER_TCP_PORT = 15000;

    public static void main(String[] args) {
        try{
            Client client = new Client(InetAddress.getByName("localhost"), SERVER_RMI_PORT, SERVER_TCP_PORT);
            client.start();

            System.exit(0);
        } catch (ConnectException | java.net.ConnectException ce) {
            System.out.println("Unable to connect with server");
            System.exit(1);
        } catch (RemoteException e) {
            System.out.println(e.getMessage());
            System.exit(2);
        } catch (InterruptedException ie) {
            System.out.println(ie.getMessage());
            System.exit(3);
        } catch (NotBoundException nbe) {
            System.out.println(nbe.getMessage());
            System.exit(4);
        } catch (UnknownHostException uhe) {
            System.out.println(uhe.getMessage());
            System.exit(5);
        } catch (IOException ioe) {
            System.out.println(ioe.getMessage());
            System.exit(6);
        }
    }
}
