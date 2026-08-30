package storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import util.AtomicFiles;
import util.FilePermissions;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared JSON document envelope: {@code {"schemaVersion": N, ...payload}}.
 * Reads are strict (malformed JSON / unknown schema throw {@link StorageException});
 * writes are atomic via {@link AtomicFiles} and owner-restricted afterwards.
 */
public final class JsonFiles {
    public static final Gson GSON = new GsonBuilder()
        .serializeNulls()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private JsonFiles() {
    }

    /** Parse the envelope and return the payload object, or null if the file does not exist. */
    public static <T> T read(Path file, int expectedSchemaVersion, Class<T> payloadType)
        throws StorageException {
        if (!Files.exists(file)) {
            return null;
        }
        JsonObject root;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(r);
            if (!parsed.isJsonObject()) {
                throw new StorageException(StorageException.Kind.INVALID_JSON,
                    "JSON 根节点不是对象: " + file);
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new StorageException(StorageException.Kind.INVALID_JSON,
                "JSON 解析失败: " + file + " (" + e.getMessage() + ")", e);
        } catch (StorageException e) {
            throw e;
        } catch (IOException e) {
            throw new StorageException(StorageException.Kind.INVALID_JSON,
                "读取失败: " + file + " (" + e.getMessage() + ")", e);
        }

        JsonElement version = root.get("schemaVersion");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()) {
            throw new StorageException(StorageException.Kind.UNKNOWN_SCHEMA,
                "缺少有效 schemaVersion: " + file);
        }
        if (version.getAsInt() != expectedSchemaVersion) {
            throw new StorageException(StorageException.Kind.UNKNOWN_SCHEMA,
                "不支持的 schemaVersion " + version.getAsInt()
                    + "（期望 " + expectedSchemaVersion + "）: " + file);
        }
        JsonElement payload = root.get("data");
        if (payload == null) {
            throw new StorageException(StorageException.Kind.INVALID_JSON,
                "缺少 data 字段: " + file);
        }
        try {
            return GSON.fromJson(payload, payloadType);
        } catch (JsonParseException e) {
            throw new StorageException(StorageException.Kind.INVALID_JSON,
                "JSON 字段解析失败: " + file + " (" + e.getMessage() + ")", e);
        }
    }

    /** Atomically write {@code {"schemaVersion": v, "data": payload}} and restrict permissions. */
    public static void write(Path file, int schemaVersion, Object payload) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", schemaVersion);
        root.add("data", GSON.toJsonTree(payload));
        AtomicFiles.writeUtf8(file, GSON.toJson(root));
        FilePermissions.restrictToOwner(file.toFile());
    }
}
