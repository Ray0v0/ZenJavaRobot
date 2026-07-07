package onebot.model;

import java.util.Collections;
import java.util.Map;

/**
 * A single segment of a OneBot message (text, image, at, reply, face, etc.).
 */
public class MessageSegment {

    private final String type;
    private final Map<String, Object> data;

    public MessageSegment(String type, Map<String, Object> data) {
        this.type = type != null ? type : "text";
        this.data = data != null ? Collections.unmodifiableMap(data) : Collections.emptyMap();
    }

    // --- Getters ---

    public String getType() { return type; }
    public Map<String, Object> getData() { return data; }

    /**
     * Convenience: get a data field as String.
     */
    public String getDataString(String key) {
        Object val = data.get(key);
        return val != null ? val.toString() : "";
    }

    @Override
    public String toString() {
        if ("text".equals(type)) {
            return "[text] " + getDataString("text");
        }
        return "[" + type + "]";
    }
}
