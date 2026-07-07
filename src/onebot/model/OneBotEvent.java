package onebot.model;

import json.Json;
import json.JsonValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Parsed OneBot v11 event from QQ. This is the universal event type that flows
 * through the handler pipeline.
 *
 */
public class OneBotEvent {

    // --- Core OneBot fields ---
    private final String postType;
    private final String detailType;
    private final String subType;
    private final long selfId;
    private final long userId;
    private final Long groupId;
    private final int messageId;
    private final List<MessageSegment> message;
    private final String rawMessage;
    private final Sender sender;
    private final long time;
    private final int font;

    // --- Extra fields for notice / request events ---
    private final Long operatorId;
    private final Long targetId;
    private final int duration;
    private final String comment;
    private final String flag;
    private final Map<String, Object> noticeData;

    public OneBotEvent(
            String postType, String detailType, String subType,
            long selfId, long userId, Long groupId, int messageId,
            List<MessageSegment> message, String rawMessage, Sender sender,
            long time, int font, Long operatorId, Long targetId,
            int duration, String comment, String flag, Map<String, Object> noticeData) {
        this.postType = postType != null ? postType : "";
        this.detailType = detailType != null ? detailType : "";
        this.subType = subType != null ? subType : "";
        this.selfId = selfId;
        this.userId = userId;
        this.groupId = groupId;
        this.messageId = messageId;
        this.message = message != null ? Collections.unmodifiableList(message) : Collections.emptyList();
        this.rawMessage = rawMessage != null ? rawMessage : "";
        this.sender = sender;
        this.time = time;
        this.font = font;
        this.operatorId = operatorId;
        this.targetId = targetId;
        this.duration = duration;
        this.comment = comment != null ? comment : "";
        this.flag = flag != null ? flag : "";
        this.noticeData = noticeData != null ? Collections.unmodifiableMap(noticeData) : Collections.emptyMap();
    }

    // --- Getters ---

    public String getPostType() { return postType; }
    public String getDetailType() { return detailType; }
    public String getSubType() { return subType; }
    public long getSelfId() { return selfId; }
    public long getUserId() { return userId; }
    public Long getGroupId() { return groupId; }
    public int getMessageId() { return messageId; }
    public List<MessageSegment> getMessage() { return message; }
    public String getRawMessage() { return rawMessage; }
    public Sender getSender() { return sender; }
    public long getTime() { return time; }
    public int getFont() { return font; }
    public Long getOperatorId() { return operatorId; }
    public Long getTargetId() { return targetId; }
    public int getDuration() { return duration; }
    public String getComment() { return comment; }
    public String getFlag() { return flag; }
    public Map<String, Object> getNoticeData() { return noticeData; }

    // --- Convenience properties ---

    /** True if this is a group message/event. */
    public boolean isGroup() {
        if ("message".equals(postType)) {
            return "group".equals(detailType);
        }
        return "group".equals(detailType) || groupId != null;
    }

    /** True if this is a private message/event. */
    public boolean isPrivate() {
        return "private".equals(detailType)
                || ("friend".equals(detailType) && ("notice".equals(postType) || "request".equals(postType)));
    }

    /** True if this is a message event. */
    public boolean isMessage() {
        return "message".equals(postType);
    }

    /** True if this is a notice event. */
    public boolean isNotice() {
        return "notice".equals(postType);
    }

    /** True if this is a group file upload notice. */
    public boolean isGroupUpload() {
        return "notice".equals(postType) && "group_upload".equals(detailType);
    }

    /** Key for session lookup: "group_&lt;id&gt;" or "private_&lt;id&gt;". */
    public String getSessionKey() {
        if (isGroup() && groupId != null) {
            return "group_" + groupId;
        }
        return "private_" + userId;
    }

    /** Concatenated text from all text segments. */
    public String getPlainText() {
        StringBuilder sb = new StringBuilder();
        for (MessageSegment seg : message) {
            if ("text".equals(seg.getType())) {
                sb.append(seg.getDataString("text"));
            }
        }
        return sb.toString().trim();
    }

    /** All image-type message segments. */
    public List<MessageSegment> getImageSegments() {
        List<MessageSegment> imgs = new ArrayList<>();
        for (MessageSegment seg : message) {
            if ("image".equals(seg.getType())) {
                imgs.add(seg);
            }
        }
        return imgs;
    }

    /** True if the message contains any image segments. */
    public boolean hasImages() {
        for (MessageSegment seg : message) {
            if ("image".equals(seg.getType())) return true;
        }
        return false;
    }

    // --- Factory: parse raw JSON from OneBot WS into OneBotEvent ---

    /**
     * Parse a raw OneBot v11 JSON node into an {@link OneBotEvent}.
     *
     * @param payload the parsed JSON tree
     * @return the event, or {@code null} if the payload is not a dispatchable event
     *         (e.g. an API echo response)
     */
    @SuppressWarnings("unchecked")
    public static OneBotEvent fromJson(JsonValue payload) {
        // Must have post_type
        JsonValue postTypeNode = payload.get("post_type");
        if (postTypeNode == null || postTypeNode.isNull()) {
            return null;
        }
        String postType = postTypeNode.asString();

        // Determine detail_type
        String detailType = "";
        if ("message".equals(postType)) {
            detailType = payload.has("message_type") ? payload.get("message_type").asString() : "";
        } else if ("notice".equals(postType)) {
            detailType = payload.has("notice_type") ? payload.get("notice_type").asString() : "";
        } else if ("request".equals(postType)) {
            detailType = payload.has("request_type") ? payload.get("request_type").asString() : "";
        } else if ("meta_event".equals(postType)) {
            detailType = payload.has("meta_event_type") ? payload.get("meta_event_type").asString() : "";
        }

        // Parse message segments
        List<MessageSegment> segments = new ArrayList<>();
        String rawMessage = "";
        JsonValue msgNode = payload.get("message");
        if (msgNode != null && msgNode.isArray()) {
            for (JsonValue item : msgNode) {
                if (item.isObject()) {
                    String type = item.has("type") ? item.get("type").asString() : "text";
                    Map<String, Object> data;
                    if (item.has("data")) {
                        try {
                            data = Json.toMap(item.get("data"));
                        } catch (Exception e) {
                            data = Collections.emptyMap();
                        }
                    } else {
                        data = Collections.emptyMap();
                    }
                    segments.add(new MessageSegment(type, data));
                }
            }
            rawMessage = payload.has("raw_message") ? payload.get("raw_message").asString("") : "";
        } else if (msgNode != null && msgNode.isString()) {
            String text = msgNode.asString();
            segments.add(new MessageSegment("text", Collections.singletonMap("text", text)));
            rawMessage = text;
        }

        // Parse sender manually from JSON node
        Sender sender = null;
        JsonValue senderNode = payload.get("sender");
        if (senderNode != null && senderNode.isObject()) {
            try {
                sender = new Sender(
                        senderNode.get("user_id").asLong(0),
                        senderNode.get("nickname").asString(""),
                        senderNode.get("card").asString(""),
                        senderNode.get("role").asString("member"),
                        senderNode.get("sex").asString("unknown"),
                        senderNode.get("age").asInt(0),
                        senderNode.get("level").asInt(0),
                        senderNode.get("title").asString("")
                );
            } catch (Exception e) {
                sender = new Sender(0, "", "", "member", "unknown", 0, 0, "");
            }
        }

        // Parse notice_data (file info for group_upload etc.)
        Map<String, Object> noticeData = Collections.emptyMap();
        if ("notice".equals(postType) && payload.has("file")) {
            try {
                noticeData = Json.toMap(payload.get("file"));
            } catch (Exception e) {
                noticeData = Collections.emptyMap();
            }
        }

        return new OneBotEvent(
                postType,
                detailType,
                payload.has("sub_type") ? payload.get("sub_type").asString("") : "",
                payload.has("self_id") ? payload.get("self_id").asLong(0) : 0,
                payload.has("user_id") ? payload.get("user_id").asLong(0) : 0,
                payload.has("group_id") && !payload.get("group_id").isNull()
                        ? payload.get("group_id").asLong() : null,
                payload.has("message_id") ? payload.get("message_id").asInt(0) : 0,
                segments,
                rawMessage,
                sender,
                payload.has("time") ? payload.get("time").asLong(0) : 0,
                payload.has("font") ? payload.get("font").asInt(0) : 0,
                payload.has("operator_id") && !payload.get("operator_id").isNull()
                        ? payload.get("operator_id").asLong() : null,
                payload.has("target_id") && !payload.get("target_id").isNull()
                        ? payload.get("target_id").asLong() : null,
                payload.has("duration") ? payload.get("duration").asInt(0) : 0,
                payload.has("comment") ? payload.get("comment").asString("") : "",
                payload.has("flag") ? payload.get("flag").asString("") : "",
                noticeData
        );
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("OneBotEvent{");
        sb.append("postType='").append(postType).append('\'');
        sb.append(", detailType='").append(detailType).append('\'');
        sb.append(", userId=").append(userId);
        if (groupId != null) sb.append(", groupId=").append(groupId);
        if (sender != null) sb.append(", sender=").append(sender.getDisplayName());
        String text = getPlainText();
        if (!text.isEmpty()) {
            sb.append(", text='").append(text.length() > 80 ? text.substring(0, 80) + "..." : text).append('\'');
        }
        sb.append('}');
        return sb.toString();
    }
}
