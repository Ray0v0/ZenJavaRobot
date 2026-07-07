package onebot.ws;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * HTTP upgrade handshake for WebSocket connections (RFC 6455 Section 4).
 *
 * <p>Reads the HTTP request from the client, validates the WebSocket upgrade
 * headers, and provides access to request headers.</p>
 */
public class WsHandshake {

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final String requestPath;
    private final Map<String, String> headers;

    WsHandshake(String requestPath, Map<String, String> headers) {
        this.requestPath = requestPath;
        this.headers = headers;
    }

    /**
     * Read a header value by name (case-insensitive).
     */
    public String getFieldValue(String name) {
        if (name == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    /** The request path from the HTTP request line, e.g. "/". */
    public String getRequestPath() {
        return requestPath;
    }

    // ---- Static helpers ----

    /**
     * Compute the {@code Sec-WebSocket-Accept} value per RFC 6455 Section 4.2.2.
     */
    static String generateAccept(String secWebSocketKey) {
        String input = secWebSocketKey + WS_GUID;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }

    // ---- Handshake I/O ----

    /**
     * Perform the server side of the WebSocket opening handshake:
     * read the HTTP upgrade request, validate it, and send the 101 response.
     *
     * @return the parsed handshake with request headers
     * @throws IOException on I/O error or invalid handshake
     */
    static WsHandshake performHandshake(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        // Read request line
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            throw new IOException("Empty request from client");
        }

        // Parse request line: GET /path HTTP/1.1
        String[] parts = requestLine.split(" ", 3);
        String method = parts.length > 0 ? parts[0] : "";
        String path = parts.length > 1 ? parts[1] : "/";

        if (!"GET".equalsIgnoreCase(method)) {
            sendError(out, 405, "Method Not Allowed");
            throw new IOException("Not a GET request: " + method);
        }

        // Read headers
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                headers.put(key, value);
            }
        }

        // Validate WebSocket upgrade
        String upgrade = getHeader(headers, "Upgrade");
        String connection = getHeader(headers, "Connection");
        String wsKey = getHeader(headers, "Sec-WebSocket-Key");
        String wsVersion = getHeader(headers, "Sec-WebSocket-Version");

        if (!"websocket".equalsIgnoreCase(upgrade)) {
            sendError(out, 400, "Missing Upgrade: websocket");
            throw new IOException("Missing Upgrade: websocket header");
        }
        if (wsKey == null || wsKey.isEmpty()) {
            sendError(out, 400, "Missing Sec-WebSocket-Key");
            throw new IOException("Missing Sec-WebSocket-Key header");
        }

        // Send 101 Switching Protocols
        String accept = generateAccept(wsKey);
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 101 Switching Protocols\r\n");
        response.append("Upgrade: websocket\r\n");
        response.append("Connection: Upgrade\r\n");
        response.append("Sec-WebSocket-Accept: ").append(accept).append("\r\n");
        response.append("\r\n");
        out.write(response.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        return new WsHandshake(path, headers);
    }

    private static String getHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int prev = -1;
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n' && prev == '\r') {
                // Remove the trailing \r
                byte[] bytes = baos.toByteArray();
                if (bytes.length > 0) {
                    return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
                }
                return "";
            }
            baos.write(b);
            prev = b;
        }
        // EOF
        if (baos.size() > 0) {
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static void sendError(OutputStream out, int code, String message) throws IOException {
        String response = "HTTP/1.1 " + code + " " + message + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Connection: close\r\n\r\n" +
                message;
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
