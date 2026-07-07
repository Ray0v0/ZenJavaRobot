package onebot.ws;

import java.io.IOException;
import java.net.*;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Abstract WebSocket server, replacing {@code org.java_websocket.server.WebSocketServer}.
 *
 * <p>Listens on a TCP port, performs the WebSocket upgrade handshake for each
 * connecting client, and dispatches frame-level events via abstract callbacks.</p>
 *
 * <p>Usage:
 * <pre>{@code
 *   WsServer server = new WsServer(new InetSocketAddress("0.0.0.0", 6198)) {
 *       public void onOpen(WsConnection conn, WsHandshake hs) { ... }
 *       public void onMessage(WsConnection conn, String text) { ... }
 *       // ... etc
 *   };
 *   server.start();
 * }</pre>
 */
public abstract class WsServer {

    private final InetSocketAddress address;
    private ServerSocket serverSocket;
    private final List<WsConnection> connections = new CopyOnWriteArrayList<>();
    private Thread acceptThread;
    private volatile boolean running = false;
    private boolean reuseAddr = true;
    private int connectionLostTimeout = 0;
    private Timer heartbeatTimer;

    public WsServer(InetSocketAddress address) {
        this.address = address;
    }

    // ---- Configuration setters (mirror Java-WebSocket API) ----

    public void setReuseAddr(boolean reuseAddr) {
        this.reuseAddr = reuseAddr;
    }

    public void setConnectionLostTimeout(int seconds) {
        this.connectionLostTimeout = seconds;
    }

    /** Get the bound address (available after {@link #start()}). */
    public InetSocketAddress getAddress() {
        if (serverSocket != null && serverSocket.isBound()) {
            return (InetSocketAddress) serverSocket.getLocalSocketAddress();
        }
        return address;
    }

    /** @return list of currently open connections */
    public List<WsConnection> getConnections() {
        return connections;
    }

    // ---- Lifecycle ----

    /**
     * Start the server: bind to the configured address and begin accepting
     * connections in a background thread.
     *
     * @throws IOException if the socket cannot be bound
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(reuseAddr);
        serverSocket.bind(address);
        running = true;

        // Heartbeat timer
        if (connectionLostTimeout > 0) {
            heartbeatTimer = new Timer("ws-heartbeat", true);
            heartbeatTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    checkHeartbeats();
                }
            }, connectionLostTimeout * 1000L, connectionLostTimeout * 1000L);
        }

        acceptThread = new Thread(this::acceptLoop, "ws-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        onStart();
    }

    /**
     * Stop the server: close all connections and the server socket.
     *
     * @param timeout maximum time to wait for shutdown in milliseconds
     */
    public void stop(int timeout) throws InterruptedException {
        running = false;

        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }

        // Close server socket to wake accept thread
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}

        // Close all connections
        for (WsConnection conn : connections) {
            try {
                conn.close(1001, "Server shutting down");
            } catch (Exception ignored) {}
        }
        connections.clear();

        // Wait for accept thread
        if (acceptThread != null) {
            acceptThread.join(Math.max(timeout, 100));
        }
    }

    // ---- Accept loop ----

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                if (!running) {
                    try { socket.close(); } catch (IOException ignored) {}
                    break;
                }

                handleNewConnection(socket);
            } catch (SocketException e) {
                // Socket closed (stop() called)
            } catch (IOException e) {
                if (running) {
                    onError(null, e);
                }
            }
        }
    }

    private void handleNewConnection(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            WsConnection conn = new WsConnection(socket);
            conn.setServer(this);
            connections.add(conn);
            onOpen(conn, conn.getHandshake());
            conn.startReader();
        } catch (IOException e) {
            // Handshake failed — close the raw socket
            try { socket.close(); } catch (IOException ignored) {}
            onError(null, e);
        }
    }

    // ---- Heartbeat ----

    private void checkHeartbeats() {
        long now = System.currentTimeMillis();
        long timeoutMs = connectionLostTimeout * 2000L; // 2x tolerance

        for (WsConnection conn : connections) {
            if (now - conn.getLastPongTime() > timeoutMs) {
                connections.remove(conn);
                try { conn.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ---- Abstract callbacks (mirror Java-WebSocket API) ----

    /** Called after the server starts listening. */
    public void onStart() {}

    /**
     * Called when a new WebSocket connection is established.
     *
     * @param conn     the new connection
     * @param handshake the HTTP upgrade handshake from the client
     */
    public abstract void onOpen(WsConnection conn, WsHandshake handshake);

    /**
     * Called when a text message is received.
     *
     * @param conn the connection that sent the message
     * @param data the UTF-8 text payload
     */
    public abstract void onMessage(WsConnection conn, String data);

    /**
     * Called when a connection is closed.
     *
     * @param conn   the connection that closed
     * @param code   WebSocket close status code, or -1
     * @param reason close reason string
     * @param remote true if the close was initiated by the client
     */
    public abstract void onClose(WsConnection conn, int code, String reason, boolean remote);

    /**
     * Called when an error occurs on a connection or globally.
     *
     * @param conn the connection (may be null for global errors)
     * @param ex   the exception
     */
    public abstract void onError(WsConnection conn, Exception ex);
}
