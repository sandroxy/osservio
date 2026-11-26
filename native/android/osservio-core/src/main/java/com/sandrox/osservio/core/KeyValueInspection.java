package com.sandrox.osservio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One account's explicitly registered storage domains. No capture, persistence or export.
 * Close before changing accounts, and dispose the old inspector. Close never closes the host store.
 * One read is admitted at a time, with no waiting query backlog. Cancellation drops delivery;
 * a platform call already in progress may finish. Callbacks may run on any thread.
 */
public final class KeyValueInspection implements AutoCloseable {
    public static final int MAX_DOMAINS = 32, MAX_KEYS = 2048, MAX_SCANNED_KEYS = 8192, MAX_KEY_BYTES = 1024,
        MAX_INDEX_BYTES = 262144, MAX_VALUE_BYTES = 65536;
    public enum Error { BUSY, CLOSED, UNAVAILABLE, UNKNOWN_DOMAIN, INVALID_KEY }
    public enum Kind { STRING, BOOLEAN, INTEGER, NUMBER, STRING_SET, ARRAY, OBJECT, DATA, DATE }
    public enum State { AVAILABLE, MISSING, UNSUPPORTED, TOO_LARGE }
    public static final class Value {
        public final Kind kind;
        public final State state;
        public final String text;
        public final boolean truncated;
        private Value(Kind kind, State state, String text, boolean truncated) {
            this.kind = kind; this.state = state; this.text = text; this.truncated = truncated;
        }
        /** Bounds UTF-8 before retaining text. A truncated value is a preview, never a complete JSON document. */
        public static Value text(Kind kind, String text) {
            BoundedText bounded = BoundedText.prefix(Objects.requireNonNull(text), MAX_VALUE_BYTES);
            if (bounded == null) return unavailable(State.UNSUPPORTED);
            return new Value(Objects.requireNonNull(kind), State.AVAILABLE, bounded.text, bounded.truncated);
        }
        public static Value unavailable(State state) {
            if (state == State.AVAILABLE) throw new IllegalArgumentException("Use text for available values");
            return new Value(null, Objects.requireNonNull(state), "", false);
        }
    }
    public static final class Domain {
        public final String id, name, backend, scope;
        public Domain(String id, String name, String backend, String scope) {
            this.id = label(id); this.name = label(name); this.backend = label(backend); this.scope = label(scope);
        }
        private static String label(String value) {
            BoundedText text = BoundedText.prefix(Objects.requireNonNull(value), MAX_KEY_BYTES);
            if (value.isEmpty() || text == null || text.truncated) throw new IllegalArgumentException("Invalid storage domain label");
            return value;
        }
    }
    public static final class Registration {
        public final Domain domain;
        final KeyValueSource source;
        public Registration(Domain domain, KeyValueSource source) {
            this.domain = Objects.requireNonNull(domain); this.source = Objects.requireNonNull(source);
        }
    }
    public static final class Keys {
        public final List<String> keys;
        public final boolean limited;
        public final long readAtMillis;
        Keys(List<String> keys, boolean limited) {
            this.keys = Collections.unmodifiableList(keys); this.limited = limited; readAtMillis = System.currentTimeMillis();
        }
    }
    public static final class Read {
        public final Value value;
        public final long readAtMillis;
        Read(Value value) { this.value = value; readAtMillis = System.currentTimeMillis(); }
    }
    public interface Completion<T> { void complete(T value, Error error); }
    public static final class Request implements AutoCloseable {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private Runnable onCancel;
        private Request() { }
        private Request(Runnable onCancel) { this.onCancel = onCancel; }
        @Override public void close() { if (cancelled.compareAndSet(false, true) && onCancel != null) { Runnable action = onCancel; onCancel = null; action.run(); } }
    }
    public final UUID owner;
    public final List<Domain> domains;
    private final Map<String, KeyValueSource> sources = new LinkedHashMap<>();
    private final Map<Object, Runnable> observers = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(), busy = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Osservio-storage-read"); thread.setDaemon(true); return thread;
    });
    public KeyValueInspection(UUID owner, List<Registration> registrations) {
        this.owner = Objects.requireNonNull(owner);
        if (registrations.size() > MAX_DOMAINS) throw new IllegalArgumentException("Too many storage domains");
        List<Domain> domains = new ArrayList<>();
        for (Registration registration : registrations) {
            if (sources.put(registration.domain.id, registration.source) != null) throw new IllegalArgumentException("Duplicate storage domain");
            domains.add(registration.domain);
        }
        this.domains = Collections.unmodifiableList(domains);
    }
    public boolean isClosed() { return closed.get(); }
    /** Receives owner invalidation, including immediate notification if already closed. Cancel when hidden. */
    public Request observeClose(Runnable observer) {
        Objects.requireNonNull(observer); Object key = new Object(); boolean notify;
        synchronized (observers) {
            notify = closed.get();
            if (!notify) {
                if (observers.size() >= 64) throw new IllegalStateException("Too many storage observers");
                observers.put(key, observer);
            }
        }
        if (notify) observer.run();
        return new Request(() -> { synchronized (observers) { observers.remove(key); } });
    }
    public Request keys(String domain, Completion<Keys> completion) {
        return submit(domain, completion, (source, request) -> {
            Map<String, Boolean> keys = new LinkedHashMap<>(); int[] bytes = {0}, visited = {0}; boolean[] limited = {false};
            boolean complete = source.visitKeys(key -> {
                if (closed.get() || request.cancelled.get() || limited[0]) return false;
                if (++visited[0] > MAX_SCANNED_KEYS) { limited[0] = true; return false; }
                BoundedText text = key == null ? null : BoundedText.prefix(key, MAX_KEY_BYTES);
                if (text == null || text.truncated) { limited[0] = true; return false; }
                if (keys.containsKey(key)) return true;
                int size = key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (keys.size() >= MAX_KEYS || bytes[0] > MAX_INDEX_BYTES - size) { limited[0] = true; return false; }
                keys.put(key, true); bytes[0] += size; return true;
            });
            List<String> result = new ArrayList<>(keys.keySet()); Collections.sort(result);
            return new Keys(result, limited[0] || !complete);
        });
    }
    public Request read(String domain, String key, Completion<Read> completion) {
        BoundedText text = key == null ? null : BoundedText.prefix(key, MAX_KEY_BYTES);
        if (text == null || text.truncated) { completion.complete(null, Error.INVALID_KEY); return new Request(); }
        return submit(domain, completion, (source, request) -> new Read(Objects.requireNonNull(source.read(key))));
    }
    private interface Operation<T> { T run(KeyValueSource source, Request request) throws Exception; }
    private <T> Request submit(String domain, Completion<T> completion, Operation<T> operation) {
        Objects.requireNonNull(completion); Request request = new Request(); KeyValueSource source;
        synchronized (sources) { source = sources.get(domain); }
        Error rejected = closed.get() ? Error.CLOSED : source == null ? Error.UNKNOWN_DOMAIN : null;
        if (rejected == null && !busy.compareAndSet(false, true)) rejected = Error.BUSY;
        if (rejected != null) { completion.complete(null, rejected); return request; }
        try {
            worker.execute(() -> {
                T value = null; Error error = null;
                try { if (!closed.get() && !request.cancelled.get()) value = operation.run(source, request); }
                catch (Exception failure) { error = Error.UNAVAILABLE; }
                finally { busy.set(false); }
                if (closed.get()) { value = null; error = Error.CLOSED; }
                if (!request.cancelled.get()) completion.complete(value, error);
            });
        } catch (RejectedExecutionException failure) { busy.set(false); completion.complete(null, Error.CLOSED); }
        return request;
    }
    /** Invalidates pending results and releases registrations; it never writes or deletes host data. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (sources) { sources.clear(); } worker.shutdown();
        List<Runnable> listeners; synchronized (observers) { listeners = new ArrayList<>(observers.values()); observers.clear(); }
        for (Runnable listener : listeners) listener.run();
    }
}
