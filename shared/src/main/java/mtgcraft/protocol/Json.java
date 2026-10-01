package mtgcraft.protocol;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Strict record-only boundary: no unknown/duplicate fields or coercion. */
public final class Json {
    public static final int MAX_BYTES = 256 * 1024;
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    public static byte[] bytes(Object value) {
        var bytes = GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("payload too large");
        return bytes;
    }
    public static <T> T read(byte[] bytes, Class<T> type) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("payload too large");
        try {
            var text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            try (var reader = new JsonReader(new StringReader(text))) {
                reader.setStrictness(Strictness.STRICT);
                var tree = readTree(reader, 0);
                if (tree.isJsonNull()) throw new IllegalArgumentException("null message");
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("trailing data");
                schema(tree, type);
                return GSON.fromJson(tree, type);
            }
        } catch (Exception invalid) { throw new IllegalArgumentException("invalid JSON schema", invalid); }
    }
    private static JsonElement readTree(JsonReader reader, int depth) throws java.io.IOException {
        if (depth > 16) throw new IllegalArgumentException("JSON too deep");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    var name = reader.nextName();
                    if (object.has(name) || object.size() >= 32) throw new IllegalArgumentException("invalid object fields");
                    object.add(name, readTree(reader, depth + 1));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                var array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) {
                    if (array.size() >= Protocol.MAX_ENTRIES * 2) throw new IllegalArgumentException("array too large");
                    array.add(readTree(reader, depth + 1));
                }
                reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("invalid JSON token");
        };
    }
    private static void schema(JsonElement value, Type type) {
        if (value.isJsonNull()) {
            if (type instanceof Class<?> c && c.isPrimitive()) throw new IllegalArgumentException("null primitive");
            return; // Compact record constructors enforce which references may be null.
        }
        if (type instanceof ParameterizedType p && p.getRawType() == List.class) {
            if (!value.isJsonArray()) throw new IllegalArgumentException("expected list");
            for (var entry : value.getAsJsonArray()) schema(entry, p.getActualTypeArguments()[0]);
            return;
        }
        var clazz = (Class<?>) type;
        if (clazz.isRecord()) {
            if (!value.isJsonObject()) throw new IllegalArgumentException("expected record");
            var object = value.getAsJsonObject();
            var fields = new HashSet<String>();
            for (var component : clazz.getRecordComponents()) {
                fields.add(component.getName());
                if (!object.has(component.getName())) throw new IllegalArgumentException("missing field");
                schema(object.get(component.getName()), component.getGenericType());
            }
            if (!fields.equals(object.keySet())) throw new IllegalArgumentException("unknown field");
            return;
        }
        if (!value.isJsonPrimitive()) throw new IllegalArgumentException("expected primitive");
        var primitive = value.getAsJsonPrimitive();
        if (clazz == String.class || clazz == UUID.class || clazz.isEnum()) {
            if (!primitive.isString()) throw new IllegalArgumentException("expected string");
            if (clazz == UUID.class && !UUID.fromString(primitive.getAsString()).toString().equals(primitive.getAsString()))
                throw new IllegalArgumentException("noncanonical UUID");
        } else if (clazz == int.class || clazz == Integer.class || clazz == long.class) {
            if (!primitive.isNumber()) throw new IllegalArgumentException("expected integer");
            if (clazz == long.class) primitive.getAsBigDecimal().longValueExact();
            else primitive.getAsBigDecimal().intValueExact();
        } else if (clazz == boolean.class) {
            if (!primitive.isBoolean()) throw new IllegalArgumentException("expected boolean");
        } else throw new IllegalArgumentException("unsupported schema type");
    }
}
