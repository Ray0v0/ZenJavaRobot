package onebot;

import json.JsonValue;
import onebot.model.ImageData;
import onebot.model.MessageSegment;
import onebot.model.OneBotEvent;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High-level OneBot v11 API caller.
 *
 * <p>Wraps {@link OneBotServer#sendApiCall} with typed helper methods.
 * When {@code sendEnabled} is {@code false}, all send methods log at INFO
 * with a {@code [DRY-RUN]} prefix instead of actually sending messages.</p>
 *
 */
public class OneBotAPI {

    private static final Logger LOG = Logger.getLogger(OneBotAPI.class.getName());

    private final OneBotServer server;
    private final boolean sendEnabled;

    public OneBotAPI(OneBotServer server, boolean sendEnabled) {
        this.server = server;
        this.sendEnabled = sendEnabled;
    }

    // =====================================================================
    // Messaging
    // =====================================================================

    /**
     * Send a private message.
     *
     * @param userId  target QQ user ID
     * @param message a string or list of OneBot segment maps
     * @return future with API result, or {@code null} in dry-run mode
     */
    public CompletableFuture<JsonValue> sendPrivateMsg(long userId, Object message) {
        if (!sendEnabled) {
            LOG.info(String.format("[DRY-RUN] sendPrivateMsg to %d: %s", userId, message));
            return CompletableFuture.completedFuture(null);
        }
        return server.sendApiCall("send_private_msg",
                Map.of("user_id", userId, "message", message), null);
    }

    /**
     * Send a group message.
     *
     * @param groupId target group ID
     * @param message a string or list of OneBot segment maps
     * @return future with API result, or {@code null} in dry-run mode
     */
    public CompletableFuture<JsonValue> sendGroupMsg(long groupId, Object message) {
        if (!sendEnabled) {
            LOG.info(String.format("[DRY-RUN] sendGroupMsg to %d: %s", groupId, message));
            return CompletableFuture.completedFuture(null);
        }
        return server.sendApiCall("send_group_msg",
                Map.of("group_id", groupId, "message", message), null);
    }

    /**
     * Universal send (auto-detects private vs group).
     *
     * @param message     a string or list of OneBot segment maps
     * @param userId      target QQ user ID (optional)
     * @param groupId     target group ID (optional)
     * @param messageType "private" or "group" (optional, inferred if omitted)
     * @return future with API result
     */
    public CompletableFuture<JsonValue> sendMsg(Object message, Long userId, Long groupId, String messageType) {
        Map<String, Object> params = new HashMap<>();
        params.put("message", message);
        if (messageType != null) params.put("message_type", messageType);
        if (userId != null) params.put("user_id", userId);
        if (groupId != null) params.put("group_id", groupId);

        if (!sendEnabled) {
            LOG.info(String.format("[DRY-RUN] sendMsg (userId=%s, groupId=%s): %s", userId, groupId, message));
            return CompletableFuture.completedFuture(null);
        }
        return server.sendApiCall("send_msg", params, null);
    }

    /**
     * Convenience: build a text message segment list from a plain string.
     */
    public static Object textMessage(String text) {
        return Collections.singletonList(
                Map.of("type", "text", "data", Map.of("text", text))
        );
    }

    /**
     * Convenience: reply to an event with text, choosing the correct send method.
     */
    public CompletableFuture<JsonValue> reply(OneBotEvent event, String text) {
        Object msg = textMessage(text);
        if (event.isGroup() && event.getGroupId() != null) {
            return sendGroupMsg(event.getGroupId(), msg);
        } else {
            return sendPrivateMsg(event.getUserId(), msg);
        }
    }

    // =====================================================================
    // Image
    // =====================================================================

    /**
     * Fetch image data from a message segment.  Blocks until the image is
     * retrieved from the QQ client.
     *
     * @param seg an image-type {@link MessageSegment}
     * @return resolved {@link ImageData}
     * @throws RuntimeException if the API call fails or times out
     */
    public ImageData getImage(MessageSegment seg) {
        String file = seg.getDataString("file");
        if (file.isEmpty()) {
            throw new IllegalArgumentException("MessageSegment has no 'file' field");
        }
        return getImage(file);
    }

    /**
     * Fetch image data by OneBot file identifier.  Blocks until the image is
     * retrieved.
     *
     * @param file the OneBot file identifier (from {@code MessageSegment.data.file})
     * @return resolved {@link ImageData}
     * @throws RuntimeException if the API call fails or times out
     */
    public ImageData getImage(String file) {
        // 1. Call get_image API
        CompletableFuture<JsonValue> future = server.sendApiCall("get_image",
                Map.of("file", file), null);
        if (future == null) {
            throw new RuntimeException("No QQ client connected");
        }

        JsonValue result;
        try {
            result = future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while fetching image", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new RuntimeException("Failed to fetch image: " + e.getMessage(), e);
        }

        if (result == null || !"ok".equals(result.path("status").asString())) {
            throw new RuntimeException("get_image API call failed: " + result);
        }

        JsonValue data = result.path("data");

        // 2. Try reading from local path (Napcat on same machine)
        String localPath = data.has("file") ? data.path("file").asString(null) : null;
        if (localPath != null) {
            File localFile = new File(localPath);
            if (localFile.exists()) {
                try {
                    return ImageData.fromLocalFile(file, localPath);
                } catch (IOException e) {
                    LOG.warning(String.format("Failed to read local image %s, trying URL fallback", localPath));
                }
            }
        }

        // 3. Fallback: download via Napcat's internal HTTP server
        //    (common pattern: http://127.0.0.1:<napcat_port>/file?...)
        String imageUrl = data.has("url") ? data.path("url").asString(null) : null;
        if (imageUrl != null) {
            try {
                return downloadImage(file, imageUrl);
            } catch (IOException e) {
                throw new RuntimeException("Failed to download image from " + imageUrl, e);
            }
        }

        // 4. Last resort: file= in get_image response contains base64
        if (data.has("file") && data.path("file").asString() != null) {
            String raw = data.path("file").asString();
            if (raw.length() > 100 && raw.matches("^[A-Za-z0-9+/=]+$")) {
                try {
                    byte[] bytes = Base64.getDecoder().decode(raw);
                    return ImageData.fromApiResult(file, bytes);
                } catch (IllegalArgumentException ignored) { /* not base64 */ }
            }
        }

        throw new RuntimeException("Could not obtain image bytes for file=" + file);
    }

    private ImageData downloadImage(String file, String url) throws IOException {
        LOG.fine(String.format("Downloading image from %s", url));
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", "ZenJavaRobot/1.0");

        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return ImageData.fromApiResult(file, out.toByteArray());
        } finally {
            conn.disconnect();
        }
    }

    // =====================================================================
    // Generic
    // =====================================================================

    /**
     * Generic OneBot API call.
     *
     * @param action OneBot action name
     * @param params action parameters
     * @return future with API result
     */
    public CompletableFuture<JsonValue> call(String action, Map<String, Object> params) {
        if (!sendEnabled) {
            LOG.info(String.format("[DRY-RUN] call action='%s' params=%s", action, params));
            return CompletableFuture.completedFuture(null);
        }
        return server.sendApiCall(action, params, null);
    }

    public boolean isSendEnabled() { return sendEnabled; }
}
