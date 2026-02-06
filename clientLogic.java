import java.io.*;
import java.net.Socket;
import java.util.function.Consumer;

public class clientLogic {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private Consumer<String> onMessageReceived;

    // This sets up the connection and starts the listener thread
    public void connect(String ip, int port, Consumer<String> callback) throws Exception {
        this.onMessageReceived = callback;
        socket = new Socket(ip, port);
        out = new PrintWriter(socket.getOutputStream(), true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        // We run this in a thread so the GUI doesn't "white out" or freeze
        new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    onMessageReceived.accept(line);
                }
            } catch (IOException e) {
                onMessageReceived.accept("DISCONNECTED");
            }
        }).start();
    }

    // A simple helper so we don't have to deal with PrintWriter in the other files
    public void send(String cmd) {
        if (out != null) {
            out.println(cmd);
        }
    }
}