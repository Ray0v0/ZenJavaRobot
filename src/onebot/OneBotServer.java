package onebot;

import config.OneBotConfig;
import json.Json;
import json.JsonValue;
import onebot.model.OneBotEvent;
import onebot.ws.WsConnection;
import onebot.ws.WsHandshake;
import onebot.ws.WsServer;

import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OneBot v11 reverse-WebSocket server.
 *
 * <p>Listens for inbound WS connections from QQ clients,
 * parses incoming JSON payloads into {@link OneBotEvent} objects and
 * provides {@link #sendApiCall} for outbound OneBot actions.</p>
 *
 */
public class OneBotServer extends WsServer {

    private static final Logger LOG = Logger.getLogger(OneBotServer.class.getName());

    private final String accessToken;

    // --- Event queue (push parsed events here for the dispatch loop) ---
    private final BlockingQueue<OneBotEvent> eventQueue = new LinkedBlockingQueue<>(4096);

    // --- WS connections: self_id (QQ bot account) -> WsConnection ---
    private final ConcurrentHashMap<Long, WsConnection> wsClients = new ConcurrentHashMap<>();

    // --- Pending API calls: seq -> CompletableFuture ---
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonValue>> pendingCalls = new ConcurrentHashMap<>();
    private int seq = 0;

    /**
     * Create the server bound to the configured host:port.
     */
    public OneBotServer(OneBotConfig config) {
        super(new InetSocketAddress(config.getHost(), config.getPort()));
        this.accessToken = config.getAccessToken();
        // Allow immediate port reuse after restart (avoids "Address already in use")
        setReuseAddr(true);
        // Reduce internal connection loss detection time
        setConnectionLostTimeout(60);
    }

    // =====================================================================
    // Lifecycle hooks (from WsServer)
    // =====================================================================

    @Override
    public void onStart() {
        LOG.info(String.format("OneBot WS server listening on ws://%s:%d",
                getAddress().getHostString(), getAddress().getPort()));
    }

    @Override
    public void onOpen(WsConnection conn, WsHandshake handshake) {
        // --- Authorization check ---
        if (accessToken != null && !accessToken.isEmpty()) {
            String auth = handshake.getFieldValue("Authorization");
            boolean valid = false;
            if (auth != null) {
                for (String prefix : new String[]{"Bearer ", "Token ", "token "}) {
                    if (auth.startsWith(prefix) && auth.substring(prefix.length()).equals(accessToken)) {
                        valid = true;
                        break;
                    }
                }
            }
            if (!valid) {
                LOG.warning(String.format("WS connection rejected: invalid token from %s",
                        conn.getRemoteSocketAddress()));
                conn.close(4001, "Invalid token");
                return;
            }
        }

        // --- Read self_id from header ---
        String selfIdStr = handshake.getFieldValue("X-Self-ID");
        long selfId = 0;
        if (selfIdStr != null && !selfIdStr.isEmpty()) {
            try {
                selfId = Long.parseLong(selfIdStr);
            } catch (NumberFormatException e) {
                LOG.warning(String.format("Invalid X-Self-ID header: '%s'", selfIdStr));
            }
        }

        // Register the connection (replace old one for same self_id)
        WsConnection old = wsClients.put(selfId, conn);
        if (old != null) {
            LOG.info(String.format("Replacing old WS connection for self_id=%d", selfId));
            old.close(4002, "Replaced by new connection");
        }

        LOG.info(String.format("OneBot WS client connected: self_id=%d from %s (%s)",
                selfId, conn.getRemoteSocketAddress(), handshake.getFieldValue("X-Client-Role")));
    }

    @Override
    public void onMessage(WsConnection conn, String data) {
        JsonValue payload;
        try {
            payload = Json.parse(data);
        } catch (Exception e) {
            LOG.warning(String.format("Received non-JSON WS message: %s",
                    data.length() > 200 ? data.substring(0, 200) : data));
            return;
        }

        // --- API result (has echo, no post_type) → route to pending calls ---
        if (!payload.has("post_type") && payload.has("echo")) {
            handleApiResult(payload);
            return;
        }

        // --- Parse into OneBotEvent ---
        OneBotEvent event = OneBotEvent.fromJson(payload);
        if (event == null) {
            return;
        }

        // Log interesting events
        switch (event.getPostType()) {
            case "meta_event":
                if ("lifecycle".equals(event.getDetailType())) {
                    LOG.info(String.format("QQ bot lifecycle: %s (self_id=%d)",
                            event.getSubType(), event.getSelfId()));
                } else if ("heartbeat".equals(event.getDetailType())) {
                    LOG.fine(String.format("Heartbeat from self_id=%d (status ok)", event.getSelfId()));
                }
                break;
            case "message":
                String text = event.getPlainText();
                LOG.info(String.format("Message: %s/%s from %s: %s",
                        event.getDetailType(), event.getSubType(),
                        event.getSender() != null ? event.getSender().getDisplayName() : event.getUserId(),
                        text.length() > 100 ? text.substring(0, 100) + "..." : text));
                break;
            case "notice":
                if (event.isGroupUpload()) {
                    String fileName = event.getNoticeData().getOrDefault("name", "").toString();
                    LOG.info(String.format("Group upload: '%s' (%s bytes) from user %d in group %d",
                            fileName,
                            event.getNoticeData().getOrDefault("size", 0),
                            event.getUserId(),
                            event.getGroupId()));
                }
                break;
        }

        // Push to event queue for dispatch
        if (!eventQueue.offer(event)) {
            LOG.severe(String.format("Event queue is full! Dropping event: %s", event.getPostType()));
        }
    }

    @Override
    public void onClose(WsConnection conn, int code, String reason, boolean remote) {
        // Remove from clients map if this connection is the one registered
        wsClients.values().removeIf(v -> v == conn);
        LOG.info(String.format("OneBot WS client disconnected: %s (code=%d, reason=%s)",
                conn.getRemoteSocketAddress(), code, reason));
    }

    @Override
    public void onError(WsConnection conn, Exception ex) {
        LOG.log(Level.SEVERE, String.format("WS error from %s: %s",
                conn != null ? conn.getRemoteSocketAddress() : "unknown", ex.getMessage()), ex);
    }

    // =====================================================================
    // API result routing
    // =====================================================================

    private void handleApiResult(JsonValue payload) {
        JsonValue echoNode = payload.get("echo");
        if (echoNode == null || echoNode.isNull()) return;

        int seq = -1;
        if (echoNode.has("seq")) {
            seq = echoNode.get("seq").asInt(-1);
        }
        if (seq < 0) return;

        CompletableFuture<JsonValue> future = pendingCalls.remove(seq);
        if (future != null && !future.isDone()) {
            future.complete(payload);
        }
    }

    // =====================================================================
    // Public API
    // =====================================================================

    /**
     * Send a OneBot API action over the WS connection and return a future that
     * completes when the QQ client responds.
     *
     * @param action  OneBot action name (e.g. "send_msg")
     * @param params  action parameters
     * @param selfId  target bot account, or {@code null} for first connected
     * @return future that resolves with the full API-result JSON, or fails on timeout
     */
    public CompletableFuture<JsonValue> sendApiCall(String action, java.util.Map<String, Object> params, Long selfId) {
        WsConnection ws = getWs(selfId);
        if (ws == null) {
            CompletableFuture<JsonValue> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("No WS client connected"));
            return f;
        }

        int currentSeq;
        synchronized (this) {
            currentSeq = ++seq;
        }

        CompletableFuture<JsonValue> future = new CompletableFuture<>();
        pendingCalls.put(currentSeq, future);

        // Build the JSON-RPC-like request
        JsonValue.JsonObject request = Json.createObject();
        request.put("action", action);
        request.set("params", Json.valueToTree(params != null ? params : java.util.Collections.emptyMap()));
        JsonValue.JsonObject echoObj = Json.createObject();
        echoObj.put("seq", currentSeq);
        request.set("echo", echoObj);

        try {
            ws.send(Json.stringify(request));
        } catch (Exception e) {
            pendingCalls.remove(currentSeq);
            future.completeExceptionally(e);
            return future;
        }

        // Apply timeout
        return future.orTimeout(30, TimeUnit.SECONDS);
    }

    /**
     * Get the WS connection for a specific self_id, or the first available.
     */
    private WsConnection getWs(Long selfId) {
        if (selfId != null) {
            return wsClients.get(selfId);
        }
        // Return first connected client
        if (wsClients.isEmpty()) return null;
        return wsClients.values().iterator().next();
    }

    /**
     * Non-blocking poll of the event queue. Used by the dispatch loop.
     *
     * @return the next event, or {@code null} if the queue is empty
     */
    public OneBotEvent pollEvent() {
        return eventQueue.poll();
    }

    /**
     * Blocking take from the event queue. Used by the dispatch loop.
     *
     * @return the next event (blocks until available)
     * @throws InterruptedException if the thread is interrupted
     */
    public OneBotEvent takeEvent() throws InterruptedException {
        return eventQueue.take();
    }

    /**
     * @return true if at least one WS client is connected
     */
    public boolean hasConnection() {
        return !wsClients.isEmpty();
    }

    /**
     * @return number of connected WS clients
     */
    public int getConnectionCount() {
        return wsClients.size();
    }
}
