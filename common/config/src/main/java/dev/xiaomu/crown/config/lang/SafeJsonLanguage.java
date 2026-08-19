package dev.xiaomu.crown.config.lang;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 只接受“字符串键 -> 字符串值”的严格 JSON 语言文件。 */
public final class SafeJsonLanguage {
    public static final int MAX_FILE_BYTES = 1_048_576;
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_KEY_LENGTH = 192;
    private static final int MAX_VALUE_LENGTH = 16_384;

    private final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public Map<String, String> parse(String source) {
        Objects.requireNonNull(source, "source");
        if (source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "Language JSON file is too large");
        }

        var result = new LinkedHashMap<String, String>();
        try (var reader = new JsonReader(new StringReader(source))) {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new IllegalArgumentException(
                        "Language JSON root must be an object");
            }
            reader.beginObject();
            while (reader.hasNext()) {
                if (result.size() >= MAX_ENTRIES) {
                    throw new IllegalArgumentException(
                            "Language JSON has too many entries");
                }
                String key = reader.nextName();
                if (key.isBlank() || key.length() > MAX_KEY_LENGTH) {
                    throw new IllegalArgumentException(
                            "Language key is blank or too long");
                }
                if (result.containsKey(key)) {
                    throw new IllegalArgumentException(
                            "Duplicate language key: " + key);
                }
                if (reader.peek() != JsonToken.STRING) {
                    throw new IllegalArgumentException(
                            "Language value must be a string: " + key);
                }
                String value = reader.nextString();
                if (value.length() > MAX_VALUE_LENGTH) {
                    throw new IllegalArgumentException(
                            "Language value is too long: " + key);
                }
                result.put(key, value);
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException(
                        "Trailing content in language JSON");
            }
        } catch (IOException | IllegalStateException exception) {
            throw new IllegalArgumentException(
                    "Invalid language JSON", exception);
        }
        return result;
    }

    public String write(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        return gson.toJson(values) + '\n';
    }
}