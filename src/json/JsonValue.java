package json;

import java.util.*;

/**
 * Abstract base class for JSON value nodes. Replaces Jackson's {@code JsonNode}.
 *
 * <p>Concrete subclasses: {@link JsonObject}, {@link JsonArray}, {@link JsonString},
 * {@link JsonNumber}, {@link JsonBoolean}, {@link JsonNull}, {@link JsonMissing}.</p>
 */
public abstract class JsonValue implements Iterable<JsonValue> {

    // ---- type checks ----

    public boolean isObject()  { return false; }
    public boolean isArray()   { return false; }
    public boolean isString()  { return false; }
    public boolean isNumber()  { return false; }
    public boolean isBoolean() { return false; }
    public boolean isNull()    { return false; }
    public boolean isMissing() { return false; }

    // ---- accessors (subclasses override) ----

    public String asString()              { return asString(""); }
    public String asString(String def)    { return def; }
    public int asInt()                    { return asInt(0); }
    public int asInt(int def)             { return def; }
    public long asLong()                  { return asLong(0); }
    public long asLong(long def)          { return def; }
    public double asDouble()              { return asDouble(0.0); }
    public double asDouble(double def)    { return def; }
    public boolean asBoolean()            { return asBoolean(false); }
    public boolean asBoolean(boolean def) { return def; }

    // ---- tree navigation ----

    /** Get a field by name (for objects). Returns null if not an object or field absent. */
    public JsonValue get(String key) { return null; }

    /** Check if a field exists (for objects). */
    public boolean has(String key) { return false; }

    /**
     * Like {@link #get} but never returns null — returns {@link JsonMissing#INSTANCE}
     * for missing fields, so chained {@code path("a").path("b")} calls don't throw NPE.
     */
    public JsonValue path(String key) {
        JsonValue v = get(key);
        return v != null ? v : JsonMissing.INSTANCE;
    }

    /** Field names (for objects). */
    public Set<String> keys() { return Collections.emptySet(); }

    /** Number of elements (for arrays) or fields (for objects). */
    public int size() { return 0; }

    /** Iterator (for arrays). Default returns empty iterator. */
    @Override
    public Iterator<JsonValue> iterator() {
        return Collections.<JsonValue>emptyList().iterator();
    }

    // ---- identity helpers (used by JsonWriter) ----

    /** Cast to JsonObject if this is one, else throw. */
    public JsonObject asObject() { throw new ClassCastException("Not a JsonObject: " + getClass().getSimpleName()); }

    /** Cast to JsonArray if this is one, else throw. */
    public JsonArray asArray() { throw new ClassCastException("Not a JsonArray: " + getClass().getSimpleName()); }

    // ---- serialization ----

    /** Serialize this value to a compact JSON string. */
    @Override
    public String toString() {
        return Json.stringify(this);
    }

    // =====================================================================
    // Concrete implementations
    // =====================================================================

    /** JSON object node. */
    public static final class JsonObject extends JsonValue {
        private final LinkedHashMap<String, JsonValue> map = new LinkedHashMap<>();

        @Override public boolean isObject() { return true; }

        public JsonObject set(String key, JsonValue value) {
            map.put(key, value != null ? value : JsonNull.INSTANCE);
            return this;
        }

        public JsonObject put(String key, String value)   { return set(key, new JsonString(value)); }
        public JsonObject put(String key, int value)       { return set(key, new JsonNumber(String.valueOf(value))); }
        public JsonObject put(String key, long value)      { return set(key, new JsonNumber(String.valueOf(value))); }
        public JsonObject put(String key, double value)    { return set(key, new JsonNumber(String.valueOf(value))); }
        public JsonObject put(String key, boolean value)   { return set(key, value ? JsonBoolean.TRUE : JsonBoolean.FALSE); }
        public JsonObject put(String key, JsonValue value) { return set(key, value); }

        @Override public JsonValue get(String key) {
            JsonValue v = map.get(key);
            return v != null ? v : JsonNull.INSTANCE;
        }

        @Override public boolean has(String key) { return map.containsKey(key); }

        @Override public Set<String> keys() { return map.keySet(); }

        @Override public int size() { return map.size(); }

        public Set<Map.Entry<String, JsonValue>> entrySet() { return map.entrySet(); }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof JsonObject)) return false;
            return map.equals(((JsonObject) o).map);
        }

        @Override
        public int hashCode() { return map.hashCode(); }
    }

    /** JSON array node. */
    public static final class JsonArray extends JsonValue implements Iterable<JsonValue> {
        private final ArrayList<JsonValue> list = new ArrayList<>();

        @Override public boolean isArray() { return true; }

        public JsonArray add(JsonValue value) {
            list.add(value != null ? value : JsonNull.INSTANCE);
            return this;
        }

        public JsonArray add(String value)   { return add(new JsonString(value)); }
        public JsonArray add(int value)       { return add(new JsonNumber(String.valueOf(value))); }
        public JsonArray add(long value)      { return add(new JsonNumber(String.valueOf(value))); }
        public JsonArray add(double value)    { return add(new JsonNumber(String.valueOf(value))); }
        public JsonArray add(boolean value)   { return add(value ? JsonBoolean.TRUE : JsonBoolean.FALSE); }

        public JsonValue get(int index) {
            if (index < 0 || index >= list.size()) return JsonNull.INSTANCE;
            return list.get(index);
        }

        @Override public int size() { return list.size(); }

        @Override public Iterator<JsonValue> iterator() { return list.iterator(); }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof JsonArray)) return false;
            return list.equals(((JsonArray) o).list);
        }

        @Override
        public int hashCode() { return list.hashCode(); }
    }

    /** JSON string node. */
    public static final class JsonString extends JsonValue {
        private final String value;

        public JsonString(String value) { this.value = value != null ? value : ""; }

        @Override public boolean isString() { return true; }

        @Override public String asString() { return value; }

        @Override public String asString(String def) { return value; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof JsonString)) return false;
            return value.equals(((JsonString) o).value);
        }

        @Override
        public int hashCode() { return value.hashCode(); }
    }

    /** JSON number node. Stores the raw string to avoid precision loss. */
    public static final class JsonNumber extends JsonValue {
        private final String raw;

        public JsonNumber(String raw) { this.raw = raw != null ? raw : "0"; }

        @Override public boolean isNumber() { return true; }

        @Override public String asString() { return raw; }

        @Override public int asInt(int def) {
            try { return Integer.parseInt(raw); }
            catch (NumberFormatException e) { return def; }
        }

        @Override public int asInt() {
            try { return Integer.parseInt(raw); }
            catch (NumberFormatException e) { return (int) Double.parseDouble(raw); }
        }

        @Override public long asLong(long def) {
            try { return Long.parseLong(raw); }
            catch (NumberFormatException e) { return def; }
        }

        @Override public long asLong() {
            try { return Long.parseLong(raw); }
            catch (NumberFormatException e) { return (long) Double.parseDouble(raw); }
        }

        @Override public double asDouble(double def) {
            try { return Double.parseDouble(raw); }
            catch (NumberFormatException e) { return def; }
        }

        @Override public double asDouble() { return Double.parseDouble(raw); }

        @Override public boolean asBoolean(boolean def) {
            return !"0".equals(raw) && !"0.0".equals(raw) && !"false".equals(raw);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof JsonNumber)) return false;
            return raw.equals(((JsonNumber) o).raw);
        }

        @Override
        public int hashCode() { return raw.hashCode(); }
    }

    /** JSON boolean node. */
    public static final class JsonBoolean extends JsonValue {
        public static final JsonBoolean TRUE  = new JsonBoolean(true);
        public static final JsonBoolean FALSE = new JsonBoolean(false);

        private final boolean value;

        private JsonBoolean(boolean value) { this.value = value; }

        public static JsonBoolean of(boolean value) { return value ? TRUE : FALSE; }

        @Override public boolean isBoolean() { return true; }

        @Override public boolean asBoolean() { return value; }

        @Override public boolean asBoolean(boolean def) { return value; }

        @Override public String asString() { return value ? "true" : "false"; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof JsonBoolean)) return false;
            return value == ((JsonBoolean) o).value;
        }

        @Override
        public int hashCode() { return Boolean.hashCode(value); }
    }

    /** JSON null node (singleton). */
    public static final class JsonNull extends JsonValue {
        public static final JsonNull INSTANCE = new JsonNull();

        private JsonNull() {}

        @Override public boolean isNull() { return true; }

        @Override public String asString() { return "null"; }

        @Override public String asString(String def) { return def; }

        @Override public boolean equals(Object o) { return o instanceof JsonNull; }

        @Override public int hashCode() { return 0; }
    }

    /**
     * Sentinel node returned by {@link #path(String)} when a field is missing.
     * All {@code as*()} methods return defaults; chained {@code path()} calls
     * keep returning this instance.
     */
    static final class JsonMissing extends JsonValue {
        static final JsonMissing INSTANCE = new JsonMissing();

        private JsonMissing() {}

        @Override public boolean isMissing() { return true; }

        @Override public String asString() { return ""; }

        @Override public String asString(String def) { return def; }

        @Override public JsonValue get(String key) { return this; }

        @Override public JsonValue path(String key) { return this; }

        @Override public boolean has(String key) { return false; }

        @Override
        public boolean equals(Object o) { return o instanceof JsonMissing; }

        @Override
        public int hashCode() { return 0; }

        @Override
        public String toString() { return ""; }
    }
}
