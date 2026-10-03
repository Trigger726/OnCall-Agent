import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Owned wire fixture: credentials enter over stdin, never command arguments or diagnostics. */
public final class AssistantSlowTcpClient {
    public static void main(String[] args) throws Exception {
        var commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        byte[] request = Base64.getDecoder().decode(commands.readLine());
        try (var socket = new Socket()) {
            // Set before the TCP handshake: pausing a Node stream alone leaves the Linux receive window autotuned.
            socket.setReceiveBufferSize(1024);
            socket.connect(new InetSocketAddress("127.0.0.1", 9971), 4000);
            socket.setSoTimeout(10000);
            System.out.println("BUFFER " + socket.getReceiveBufferSize());
            System.out.flush();
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            var input = socket.getInputStream();
            var preview = new ByteArrayOutputStream();
            byte[] marker = "event:token".getBytes(StandardCharsets.US_ASCII);
            int matched = 0;
            while (matched < marker.length) {
                int next = input.read();
                if (next == -1) throw new IllegalStateException("Connection ended before first token");
                preview.write(next);
                if (preview.size() > 65536) throw new IllegalStateException("First token exceeds fixture header budget");
                matched = next == marker[matched] ? matched + 1 : (next == marker[0] ? 1 : 0);
            }
            System.out.println("PAUSED " + Base64.getEncoder().encodeToString(preview.toByteArray()));
            System.out.flush();
            if (!"RESUME".equals(commands.readLine())) throw new IllegalStateException("Missing explicit resume command");
            var remainder = new ByteArrayOutputStream();
            byte[] block = new byte[8192];
            for (int size; (size = input.read(block)) != -1;) {
                remainder.write(block, 0, size);
                if (remainder.size() > 4_000_000) throw new IllegalStateException("Fixture response exceeds bounded wire budget");
            }
            System.out.println("DRAINED " + Base64.getEncoder().encodeToString(remainder.toByteArray()));
            System.out.flush();
        }
    }
}
