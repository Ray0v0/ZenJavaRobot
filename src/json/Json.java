package json;

import java.util.*;

/**
 * JSON parser, serializer, and conversion utilities.
 *
 * <p>Replaces Jackson's {@code ObjectMapper} with a minimal recursive-descent
 * parser and a tree-based serializer.</p>
 */
public final class Json {

    private Json() { /* utility class */ }

    // =====================================================================
    // Parsing
    // =====================================================================

    /**
     * Parse a JSON string into a {@link JsonValue} tree.
     *
     * @throws JsonParseException on malformed input
     */
    public static JsonValue parse(String json) {
        if (json == null || json.isEmpty()) {
            throw new JsonParseException("Empty input");
        }
        Parser p = new Parser(json);
        JsonValue v = p.parseValue();
        p.skipWhitespace();
        if (p.pos < p.len) {
            throw p.error("Unexpected trailing characters");
        }
        return v;
    }

    // =====================================================================
    // Factory methods
    // =====================================================================

    public static JsonValue.JsonObject createObject() { return new JsonValue.JsonObject(); }
    public static JsonValue.JsonArray  createArray()  { return new JsonValue.JsonArray(); }

    // =====================================================================
    // Serialization
    // =====================================================================

    /** Serialize a {@link JsonValue} tree to a compact JSON string. */
    public static String stringify(JsonValue value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, JsonValue v) {
        if (v == null || v.isNull()) {
            sb.append("null");
        } else if (v instanceof JsonValue.JsonObject) {
            writeObject(sb, (JsonValue.JsonObject) v);
        } else if (v instanceof JsonValue.JsonArray) {
            writeArray(sb, (JsonValue.JsonArray) v);
        } else if (v instanceof JsonValue.JsonString) {
            writeString(sb, v.asString());
        } else if (v instanceof JsonValue.JsonNumber) {
            sb.append(v.asString());
        } else if (v instanceof JsonValue.JsonBoolean) {
            sb.append(v.asBoolean() ? "true" : "false");
        } else {
            sb.append("null");
        }
    }

    private static void writeObject(StringBuilder sb, JsonValue.JsonObject obj) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, JsonValue> e : obj.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeString(sb, e.getKey());
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, JsonValue.JsonArray arr) {
        sb.append('[');
        for (int i = 0; i < arr.size(); i++) {
            if (i > 0) sb.append(',');
            writeValue(sb, arr.get(i));
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // =====================================================================
    // Conversion: JsonValue <-> Java Map/List
    // =====================================================================

    /**
     * Deep-convert a {@link JsonValue} tree to plain Java {@code Map<String,Object>}
     * and {@code List<Object>} structures.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(JsonValue value) {
        if (value == null || value.isNull() || value.isMissing()) return Collections.emptyMap();
        if (value.isObject()) {
            return (Map<String, Object>) convertNode(value);
        }
        return Collections.emptyMap();
    }

    /**
     * Deep-convert a {@link JsonValue} to Java object: Map, List, String, Long,
     * Double, Boolean, or null.
     */
    public static Object convertNode(JsonValue node) {
        if (node == null || node.isNull() || node.isMissing()) return null;
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : node.keys()) {
                map.put(key, convertNode(node.get(key)));
            }
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonValue item : node) {
                list.add(convertNode(item));
            }
            return list;
        }
        if (node.isString())  return node.asString();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) {
            String raw = node.asString();
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                return node.asDouble();
            }
            long l = node.asLong(Long.MIN_VALUE);
            if (l != Long.MIN_VALUE) return l;
            return node.asDouble();
        }
        return null;
    }

    /**
     * Deep-convert a Java Map/List structure into a {@link JsonValue} tree.
     */
    @SuppressWarnings("unchecked")
    public static JsonValue mapToJson(Object obj) {
        if (obj == null) return JsonValue.JsonNull.INSTANCE;
        if (obj instanceof Map) {
            JsonValue.JsonObject jo = createObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
                jo.set(String.valueOf(e.getKey()), mapToJson(e.getValue()));
            }
            return jo;
        }
        if (obj instanceof List) {
            JsonValue.JsonArray ja = createArray();
            for (Object item : (List<?>) obj) {
                ja.add(mapToJson(item));
            }
            return ja;
        }
        if (obj instanceof String)  return new JsonValue.JsonString((String) obj);
        if (obj instanceof Boolean) return JsonValue.JsonBoolean.of((Boolean) obj);
        if (obj instanceof Integer)  return new JsonValue.JsonNumber(obj.toString());
        if (obj instanceof Long)     return new JsonValue.JsonNumber(obj.toString());
        if (obj instanceof Double)   return new JsonValue.JsonNumber(obj.toString());
        if (obj instanceof Float)    return new JsonValue.JsonNumber(obj.toString());
        if (obj instanceof Number)   return new JsonValue.JsonNumber(obj.toString());
        return new JsonValue.JsonString(obj.toString());
    }

    /**
     * Convert a Java object to a JSON tree node — alias for {@link #mapToJson(Object)}.
     * Used as replacement for Jackson's {@code ObjectMapper.valueToTree()}.
     */
    public static JsonValue valueToTree(Object obj) {
        return mapToJson(obj);
    }

    // =====================================================================
    // Recursive-descent parser
    // =====================================================================

    private static class Parser {
        private final String in;
        private final int len;
        int pos;

        Parser(String in) {
            this.in = in;
            this.len = in.length();
            this.pos = 0;
        }

        void skipWhitespace() {
            while (pos < len && Character.isWhitespace(in.charAt(pos))) pos++;
        }

        char peek() {
            skipWhitespace();
            if (pos >= len) throw error("Unexpected end of input");
            return in.charAt(pos);
        }

        char next() {
            skipWhitespace();
            if (pos >= len) throw error("Unexpected end of input");
            return in.charAt(pos++);
        }

        JsonValue parseValue() {
            char c = peek();
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': case 'f': return parseKeyword();
                case 'n': return parseNull();
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
                    throw error("Unexpected character: '" + c + "'");
            }
        }

        JsonValue.JsonObject parseObject() {
            JsonValue.JsonObject obj = createObject();
            next(); // consume '{'
            skipWhitespace();
            if (pos < len && in.charAt(pos) == '}') {
                pos++; // empty object
                return obj;
            }
            while (true) {
                skipWhitespace();
                char c = pos < len ? in.charAt(pos) : 0;
                if (c != '"') throw error("Expected string key, got '" + c + "'");
                String key = parseString().asString();
                skipWhitespace();
                if (pos >= len || in.charAt(pos) != ':') throw error("Expected ':' after key");
                pos++; // consume ':'
                obj.set(key, parseValue());
                skipWhitespace();
                if (pos < len && in.charAt(pos) == ',') {
                    pos++;
                } else {
                    break;
                }
            }
            if (pos >= len || in.charAt(pos) != '}') throw error("Expected '}'");
            pos++;
            return obj;
        }

        JsonValue.JsonArray parseArray() {
            JsonValue.JsonArray arr = createArray();
            next(); // consume '['
            skipWhitespace();
            if (pos < len && in.charAt(pos) == ']') {
                pos++; // empty array
                return arr;
            }
            while (true) {
                arr.add(parseValue());
                skipWhitespace();
                if (pos < len && in.charAt(pos) == ',') {
                    pos++;
                } else {
                    break;
                }
            }
            if (pos >= len || in.charAt(pos) != ']') throw error("Expected ']'");
            pos++;
            return arr;
        }

        JsonValue.JsonString parseString() {
            pos++; // consume opening '"'
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = in.charAt(pos++);
                if (c == '"') return new JsonValue.JsonString(sb.toString());
                if (c == '\\') {
                    if (pos >= len) throw error("Unexpected end after escape");
                    char esc = in.charAt(pos++);
                    switch (esc) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > len) throw error("Unexpected end in \\u escape");
                            String hex = in.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                            break;
                        default:
                            sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw error("Unterminated string");
        }

        JsonValue.JsonBoolean parseKeyword() {
            if (in.startsWith("true", pos)) {
                pos += 4;
                return JsonValue.JsonBoolean.TRUE;
            }
            if (in.startsWith("false", pos)) {
                pos += 5;
                return JsonValue.JsonBoolean.FALSE;
            }
            throw error("Unknown keyword");
        }

        JsonValue.JsonNull parseNull() {
            if (in.startsWith("null", pos)) {
                pos += 4;
                return JsonValue.JsonNull.INSTANCE;
            }
            throw error("Unknown keyword");
        }

        JsonValue.JsonNumber parseNumber() {
            int start = pos;
            if (pos < len && in.charAt(pos) == '-') pos++;
            while (pos < len && in.charAt(pos) >= '0' && in.charAt(pos) <= '9') pos++;
            if (pos < len && in.charAt(pos) == '.') {
                pos++;
                while (pos < len && in.charAt(pos) >= '0' && in.charAt(pos) <= '9') pos++;
            }
            if (pos < len && (in.charAt(pos) == 'e' || in.charAt(pos) == 'E')) {
                pos++;
                if (pos < len && (in.charAt(pos) == '+' || in.charAt(pos) == '-')) pos++;
                while (pos < len && in.charAt(pos) >= '0' && in.charAt(pos) <= '9') pos++;
            }
            return new JsonValue.JsonNumber(in.substring(start, pos));
        }

        JsonParseException error(String msg) {
            int ctxStart = Math.max(0, pos - 20);
            int ctxEnd = Math.min(len, pos + 20);
            String ctx = in.substring(ctxStart, ctxEnd).replace("\n", "\\n");
            return new JsonParseException(
                    msg + " at position " + pos + " near: ..." + ctx + "...");
        }
    }

    /** Exception thrown on malformed JSON input. */
    public static class JsonParseException extends RuntimeException {
        public JsonParseException(String message) {
            super(message);
        }
    }
}
