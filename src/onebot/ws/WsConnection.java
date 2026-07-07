package onebot.ws;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A single WebSocket connection, wrapping a {@link Socket}.
 *
 * <p>Handles frame-level I/O per RFC 6455. After construction (which performs
 * the HTTP upgrade handshake), call {@link #startReader()} to begin reading frames
 * in a background daemon thread.</p>
 */
public class WsConnection {

    // RFC 6455 frame opcodes
    static final int OPCODE_CONTINUATION = 0x0;
    static final int OPCODE_TEXT         = 0x1;
    static final int OPCODE_BINARY       = 0x2;
    static final int OPCODE_CLOSE        = 0x8;
    static final int OPCODE_PING         = 0x9;
    static final int OPCODE_PONG         = 0xA;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final WsHandshake handshake;
    private WsServer server;
    private volatile boolean open = true;
    private volatile long lastPongTime;

    /**
     * Create a connection from an accepted socket, performing the WebSocket
     * handshake immediately.
     *
     * @throws IOException if the handshake fails
     */
    public WsConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.handshake = WsHandshake.performHandshake(socket);
        this.lastPongTime = System.currentTimeMillis();
    }

    void setServer(WsServer server) { this.server = server; }

    /** The handshake information from the HTTP upgrade request. */
    public WsHandshake getHandshake() { return handshake; }

    /** Client's remote address. */
    public java.net.InetSocketAddress getRemoteSocketAddress() {
        return (java.net.InetSocketAddress) socket.getRemoteSocketAddress();
    }

    /** Whether the connection is still open. */
    public boolean isOpen() { return open && !socket.isClosed(); }

    /** Get last pong timestamp (for heartbeat). */
    long getLastPongTime() { return lastPongTime; }

    // ---- Sending ----

    /**
     * Send a text frame to the client (unmasked, as required for server→client).
     */
    public synchronized void send(String text) {
        if (!isOpen()) return;
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        try {
            writeFrame(OPCODE_TEXT, payload);
            out.flush();
        } catch (IOException e) {
            handleError(e);
        }
    }

    /**
     * Close this connection, sending a close frame first.
     */
    public void close(int code, String reason) {
        if (!open) return;
        open = false;
        try {
            byte[] reasonBytes = reason != null
                    ? reason.getBytes(StandardCharsets.UTF_8) : new byte[0];
            byte[] payload = new byte[2 + reasonBytes.length];
            payload[0] = (byte) ((code >> 8) & 0xFF);
            payload[1] = (byte) (code & 0xFF);
            System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
            writeFrame(OPCODE_CLOSE, payload);
            out.flush();
        } catch (IOException ignored) {
            // best effort
        } finally {
            closeSocket();
        }
    }

    public void close() {
        close(1000, "");
    }

    // ---- Frame I/O ----

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        int len = payload.length;

        // FIN=1, opcode
        out.write(0x80 | (opcode & 0x0F));

        // Payload length (unmasked: no mask bit set)
        if (len < 126) {
            out.write(len);
        } else if (len <= 65535) {
            out.write(126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((len >> (i * 8)) & 0xFF));
            }
        }

        out.write(payload);
    }

    /**
     * Start the reader thread. Reads frames in a loop and dispatches to
     * the server callbacks.
     */
    public void startReader() {
        Thread reader = new Thread(this::readLoop, "ws-reader-" + getRemoteSocketAddress());
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop() {
        ByteArrayOutputStream messageBuffer = new ByteArrayOutputStream();
        int messageOpcode = 0;

        try {
            while (open && !socket.isClosed()) {
                FrameData frame = readFrame();
                if (frame == null) break;

                switch (frame.opcode) {
                    case OPCODE_TEXT:
                        // Start of a (possibly fragmented) text message
                        messageBuffer.reset();
                        messageOpcode = OPCODE_TEXT;
                        messageBuffer.write(frame.payload);
                        if (frame.fin) {
                            dispatchText(messageBuffer);
                        }
                        break;

                    case OPCODE_CONTINUATION:
                        messageBuffer.write(frame.payload);
                        if (frame.fin) {
                            if (messageOpcode == OPCODE_TEXT) {
                                dispatchText(messageBuffer);
                            }
                            messageBuffer.reset();
                            messageOpcode = 0;
                        }
                        break;

                    case OPCODE_CLOSE:
                        // Parse close code
                        int code = 1000;
                        String reason = "";
                        if (frame.payload != null && frame.payload.length >= 2) {
                            code = ((frame.payload[0] & 0xFF) << 8) | (frame.payload[1] & 0xFF);
                            if (frame.payload.length > 2) {
                                reason = new String(frame.payload, 2,
                                        frame.payload.length - 2, StandardCharsets.UTF_8);
                            }
                        }
                        // Echo close frame back
                        try {
                            writeFrame(OPCODE_CLOSE, frame.payload);
                            out.flush();
                        } catch (IOException ignored) {}
                        open = false;
                        if (server != null) server.onClose(this, code, reason, true);
                        closeSocket();
                        return;

                    case OPCODE_PING:
                        // Reply with pong (same payload)
                        try {
                            writeFrame(OPCODE_PONG, frame.payload);
                            out.flush();
                        } catch (IOException ignored) {}
                        break;

                    case OPCODE_PONG:
                        lastPongTime = System.currentTimeMillis();
                        break;

                    case OPCODE_BINARY:
                        // We don't process binary, but accumulate if fragmented
                        break;
                }
            }
        } catch (SocketException e) {
            // Connection closed
        } catch (IOException e) {
            handleError(e);
        } finally {
            if (open) {
                open = false;
                if (server != null) server.onClose(this, -1, "IO error", false);
                closeSocket();
            }
        }
    }

    private void dispatchText(ByteArrayOutputStream buffer) {
        String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        if (server != null) {
            try {
                server.onMessage(this, text);
            } catch (Exception e) {
                handleError(e);
            }
        }
    }

    private void handleError(Exception e) {
        if (server != null) {
            try {
                server.onError(this, e);
            } catch (Exception ignored) {}
        }
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {}
    }

    // ---- Frame decoding ----

    private FrameData readFrame() throws IOException {
        // Read first 2 bytes
        int b0 = in.read();
        if (b0 == -1) return null;
        int b1 = in.read();
        if (b1 == -1) return null;

        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long payloadLen = b1 & 0x7F;

        // Extended payload length
        if (payloadLen == 126) {
            payloadLen = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (payloadLen == 127) {
            payloadLen = 0;
            for (int i = 0; i < 8; i++) {
                payloadLen = (payloadLen << 8) | (in.read() & 0xFF);
            }
        }

        // Mask key (only for client→server frames)
        byte[] maskKey = null;
        if (masked) {
            maskKey = new byte[4];
            int read = in.read(maskKey);
            if (read < 4) throw new IOException("Incomplete mask key");
        }

        // Payload
        byte[] payload = new byte[(int) payloadLen];
        int total = 0;
        while (total < payloadLen) {
            int r = in.read(payload, total, (int) (payloadLen - total));
            if (r == -1) throw new IOException("Incomplete frame payload");
            total += r;
        }

        // Unmask
        if (masked && maskKey != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= maskKey[i % 4];
            }
        }

        return new FrameData(fin, opcode, payload);
    }

    private static class FrameData {
        final boolean fin;
        final int opcode;
        final byte[] payload;

        FrameData(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }
    }
}
