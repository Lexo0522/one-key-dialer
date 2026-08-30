package storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON dial history document. Missing file = empty history;
 * malformed JSON / unknown schemaVersion throw {@link StorageException}.
 */
public class HistoryStore {
    public static final int SCHEMA_VERSION = 1;

    private final Path file;

    public HistoryStore(File file) {
        this.file = file.toPath();
    }

    /** @return stored rows ({@code [time, operation, account, result, duration, traffic]}), or null when missing. */
    public List<String[]> load() throws StorageException {
        Document doc = JsonFiles.read(file, SCHEMA_VERSION, Document.class);
        if (doc == null) {
            return null;
        }
        List<String[]> out = new ArrayList<>();
        if (doc.data == null) return out;
        for (HistoryRecord r : doc.data) {
            if (r == null) continue;
            out.add(new String[]{
                nn(r.time), nn(r.operation), nn(r.account),
                nn(r.result), nn(r.duration), nn(r.traffic)
            });
        }
        return out;
    }

    public void save(List<String[]> records) throws IOException {
        List<HistoryRecord> rows = new ArrayList<>(records.size());
        for (String[] r : records) {
            if (r == null) continue;
            rows.add(new HistoryRecord(
                at(r, 0), at(r, 1), at(r, 2), at(r, 3), at(r, 4), at(r, 5)));
        }
        JsonFiles.write(file, SCHEMA_VERSION, new Document(rows));
    }

    public File getFile() {
        return file.toFile();
    }

    private static String at(String[] row, int i) {
        return i < row.length && row[i] != null ? row[i] : "";
    }

    private static String nn(String s) {
        return s != null ? s : "";
    }

    private static final class HistoryRecord {
        // non-final: populated reflectively by Gson on load
        private String time;
        private String operation;
        private String account;
        private String result;
        private String duration;
        private String traffic;

        HistoryRecord(String time, String operation, String account,
                      String result, String duration, String traffic) {
            this.time = time;
            this.operation = operation;
            this.account = account;
            this.result = result;
            this.duration = duration;
            this.traffic = traffic;
        }
    }

    private static final class Document {
        private List<HistoryRecord> data;

        Document() {
        }

        Document(List<HistoryRecord> data) {
            this.data = data;
        }
    }
}
