package forge.sim;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Line-based JSON socket between Forge's decision points and the Python bridge.
 *
 * The persistent ForgeServer accepts a client connection per match and binds it
 * here; PlayerControllerBridge then calls request() at each decision. One match
 * at a time, so a single bound socket is sufficient.
 */
public final class Channel {
    private static Socket sock;
    private static BufferedReader in;
    private static BufferedWriter out;

    private Channel() {}

    /** Bind an accepted client socket for the duration of one match. */
    public static synchronized void bind(Socket s) throws IOException {
        sock = s;
        in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
    }

    public static synchronized void close() {
        try { if (sock != null) sock.close(); } catch (IOException ignore) { }
        sock = null; in = null; out = null;
    }

    /** Send one JSON request line, block for one JSON reply line. "" if no client. */
    public static synchronized String request(String json) {
        if (out == null || in == null) return "";
        try {
            out.write(json);
            out.write("\n");
            out.flush();
            String line = in.readLine();
            return line == null ? "" : line;
        } catch (IOException e) {
            return "";
        }
    }
}
