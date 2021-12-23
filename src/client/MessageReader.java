package client;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MessageReader implements Runnable{
    private static final int bufflen = 4096;
    private static final int millisTimeout = 1500;

    private final Progetto progetto;
    private final List<String> messaggi;

    private boolean listen; //true se devo continuare a ricevere e salvare i messaggi

    public MessageReader(Progetto progetto) {
        this.progetto = progetto;
        messaggi = new ArrayList<String>();

        listen = true;
    }

    @Override
    public void run() {
        while(listen) {
            try {
                //mi unisco al gruppo multicast
                byte[] buffer = new byte[bufflen];
                MulticastSocket ms = new MulticastSocket(progetto.getChatMulticastsocketNumber());
                ms.setSoTimeout(millisTimeout);
                ms.joinGroup(progetto.getChatMulticastAddress());

                //attendo la ricezione di un messaggio
                DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
                ms.receive(dp);
                String res = new String(buffer, StandardCharsets.UTF_8);
                synchronized (messaggi) {
                    messaggi.add(res); //salvo il messaggio ricevuto nella lista
                }
            } catch (SocketTimeoutException ignored) {
                //setto un timeout per poter essere ragionevolmente reattivo alla richiesta del client di terminare la ricezione di messaggi
            } catch (IOException e) {
                System.out.println(e.getMessage());
                return;
            }
        }
    }

    /**
     * termina il task
     */
    public void stop() {
        this.listen = false;
    }

    /**
     *
     * @return il progetto della quale chat stiamo ricevendo i messaggi
     */
    public Progetto getProgetto() {
        return progetto;
    }

    /**
     *
     * @return lista dei messaggi ricevuti
     */
    public List<String> getMessaggi() {
        synchronized (messaggi) {
            List<String> returnList = new ArrayList<>(messaggi);
            messaggi.clear();
            return returnList;
        }
    }
}
