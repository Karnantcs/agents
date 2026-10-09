package dev.agentbox.orchestrator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** In-memory log of user and agent messages. Keeps the newest {@value #LIMIT} entries. */
@Component
public class ActivityLog {

    static final int LIMIT = 500;

    private final List<Entry> entries = new ArrayList<>();
    private long nextId = 1;

    public synchronized Entry open(String from, String to, String message, Long parentId) {
        Long parent = parentId != null ? parentId : workingId(from);
        Entry entry = new Entry(nextId++, Instant.now(), from, to, message, parent, System.nanoTime());
        entries.add(entry);
        trim();
        return entry;
    }

    public synchronized void markWorking(long id) {
        Entry entry = find(id);
        if (entry != null && "sent".equals(entry.status)) {
            entry.status = "working";
        }
    }

    public synchronized void complete(long id, String reply) {
        finish(id, "done", reply == null ? "" : reply);
    }

    public synchronized void fail(long id, String error) {
        finish(id, "error", error == null ? "request failed" : error);
    }

    public synchronized void recordTool(String agent, String tool, String input, String output, long parentId) {
        String text = output == null ? "" : output;
        Entry entry = new Entry(nextId++, Instant.now(), agent, tool, input == null ? "" : input, parentId, System.nanoTime());
        entry.kind = "tool";
        entry.status = text.startsWith("error:") ? "error" : "done";
        entry.reply = text;
        entries.add(entry);
        trim();
    }

    public synchronized List<View> list(String agent) {
        String filter = agent == null ? "" : agent.trim();
        List<View> views = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (!filter.isEmpty() && !filter.equals(entry.from) && !filter.equals(entry.to)) {
                continue;
            }
            views.add(entry.view());
        }
        return views;
    }

    public synchronized Live live(String agent) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (agent.equals(entry.to) && "message".equals(entry.kind) && inFlight(entry.status)) {
                return new Live("busy", entry.message, entry.from);
            }
        }
        return new Live("idle", null, null);
    }

    synchronized void clear() {
        entries.clear();
        nextId = 1;
    }

    private void finish(long id, String status, String reply) {
        Entry entry = find(id);
        if (entry == null || !inFlight(entry.status)) {
            return;
        }
        entry.status = status;
        entry.reply = reply;
        entry.durationMillis = Duration.ofNanos(System.nanoTime() - entry.startedNanos).toMillis();
    }

    private Long workingId(String from) {
        if (from == null || from.isBlank() || "user".equals(from)) {
            return null;
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (from.equals(entry.to) && "message".equals(entry.kind) && inFlight(entry.status)) {
                return entry.id;
            }
        }
        return null;
    }

    private Entry find(long id) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (entry.id == id) {
                return entry;
            }
        }
        return null;
    }

    private void trim() {
        while (entries.size() > LIMIT) {
            int index = 0;
            if (inFlight(entries.get(0).status)) {
                index = -1;
                for (int i = 0; i < entries.size(); i++) {
                    if (!inFlight(entries.get(i).status)) {
                        index = i;
                        break;
                    }
                }
                if (index < 0) {
                    return;
                }
            }
            entries.remove(index);
        }
    }

    private static boolean inFlight(String status) {
        return "sent".equals(status) || "working".equals(status);
    }

    public record View(
            long id,
            Instant timestamp,
            String from,
            String to,
            String message,
            String status,
            String reply,
            Long durationMillis,
            Long parentId,
            String kind) {}

    public record Live(String availability, String task, String from) {}

    public static final class Entry {
        private final long id;
        private final Instant timestamp;
        private final String from;
        private final String to;
        private final String message;
        private final Long parentId;
        private final long startedNanos;
        private String status = "sent";
        private String reply;
        private Long durationMillis;
        private String kind = "message";

        private Entry(long id, Instant timestamp, String from, String to, String message, Long parentId, long startedNanos) {
            this.id = id;
            this.timestamp = timestamp;
            this.from = from;
            this.to = to;
            this.message = message;
            this.parentId = parentId;
            this.startedNanos = startedNanos;
        }

        public long id() {
            return id;
        }

        private View view() {
            return new View(id, timestamp, from, to, message, status, reply, durationMillis, parentId, kind);
        }
    }
}
