package com.sandrox.osservio.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Applied criteria are private to the viewer unless its host supplies a durable owner. */
final class InspectorRecentFilters {
    interface Loaded { void accept(List<InspectorQuery> items); }
    private static final int LIMIT = 10, ENTRY_BYTES = 16384;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String name;
    private List<InspectorQuery> memory = new ArrayList<>();
    private File file;
    private int revision;
    Runnable ownerChanged;
    InspectorRecentFilters(String name) { this.name = name; }

    boolean owner(Context context, UUID owner) {
        File next = owner == null ? null : new File(new File(context.getNoBackupFilesDir(), "osservio-recent-filters"), owner + "-" + name + ".json");
        if (java.util.Objects.equals(file, next)) return false;
        file = next; revision++; memory = new ArrayList<>();
        if (ownerChanged != null) { ownerChanged.run(); ownerChanged = null; }
        return true;
    }
    void load(Loaded callback) {
        File source = file;
        if (source == null) { callback.accept(new ArrayList<>(memory)); return; }
        int stamp = revision;
        IO.execute(() -> {
            List<InspectorQuery> result = read(source);
            main.post(() -> {
                if (!source.equals(file)) return;
                if (stamp == revision && result != null) memory = result;
                callback.accept(new ArrayList<>(memory));
            });
        });
    }
    void update(InspectorQuery query, boolean remove) {
        InspectorQuery preset = query.copy(); preset.text = "";
        for (Iterator<Set<String>> it = preset.values.values().iterator(); it.hasNext();) if (it.next().isEmpty()) it.remove();
        if (!eligible(preset)) return;
        revision++; memory = updated(memory, preset, remove);
        File destination = file;
        if (destination == null) return;
        IO.execute(() -> {
            List<InspectorQuery> existing = read(destination);
            if (existing == null) return;
            File parent = destination.getParentFile();
            if (parent == null || !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) return;
            AtomicFile atomic = new AtomicFile(destination);
            FileOutputStream output = null;
            try {
                JSONArray rows = new JSONArray();
                for (InspectorQuery item : updated(existing, preset, remove)) rows.put(encode(item));
                output = atomic.startWrite(); output.write(rows.toString().getBytes(StandardCharsets.UTF_8)); atomic.finishWrite(output);
            } catch (IOException | JSONException failure) { if (output != null) atomic.failWrite(output); }
        });
    }
    private static List<InspectorQuery> updated(List<InspectorQuery> items, InspectorQuery preset, boolean remove) {
        List<InspectorQuery> result = new ArrayList<>();
        if (!remove) result.add(preset);
        for (InspectorQuery item : items) if (result.size() < LIMIT && !item.sameAs(preset)) result.add(item);
        return result;
    }
    private static boolean eligible(InspectorQuery q) {
        if (q.activeCount() == 0 || q.minimumDurationMillis < 0 ||
            !(q.recentMinutes == 0 || q.recentMinutes == 5 || q.recentMinutes == 30 || q.recentMinutes == 60 || q.recentMinutes == 1440)) return false;
        int count = 0, length = q.excludedText.length() + q.fieldPath.length() + q.fieldValue.length();
        if (length > ENTRY_BYTES) return false;
        for (Set<String> values : q.values.values()) {
            count += values.size(); if (count > 128) return false;
            for (String value : values) { length += value.length(); if (value.length() > 4096 || length > ENTRY_BYTES) return false; }
        }
        try { return encode(q).toString().getBytes(StandardCharsets.UTF_8).length <= ENTRY_BYTES; }
        catch (JSONException invalid) { return false; }
    }
    private static JSONObject encode(InspectorQuery q) throws JSONException {
        JSONObject values = new JSONObject();
        for (Map.Entry<InspectorQuery.Field, Set<String>> group : q.values.entrySet()) {
            values.put(group.getKey().name(), new JSONArray(new java.util.TreeSet<>(group.getValue())));
        }
        return new JSONObject().put("fields", values).put("time", q.recentMinutes).put("duration", q.minimumDurationMillis)
            .put("exclusion", q.excludedText).put("path", q.fieldPath).put("value", q.fieldValue);
    }
    private static List<InspectorQuery> read(File file) {
        AtomicFile atomic = new AtomicFile(file);
        try (java.io.FileInputStream input = atomic.openRead()) {
            long size = input.getChannel().size();
            if (size > (long) LIMIT * (ENTRY_BYTES + 4)) return null;
            byte[] bytes = new byte[(int) size]; new java.io.DataInputStream(input).readFully(bytes);
            JSONArray rows = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            if (rows.length() > LIMIT) return null;
            List<InspectorQuery> result = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i), values = row.getJSONObject("fields");
                InspectorQuery q = new InspectorQuery(); q.recentMinutes = row.getInt("time"); q.minimumDurationMillis = row.getLong("duration");
                q.excludedText = row.getString("exclusion"); q.fieldPath = row.getString("path"); q.fieldValue = row.getString("value");
                for (Iterator<String> keys = values.keys(); keys.hasNext();) {
                    String key = keys.next(); JSONArray options = values.getJSONArray(key); Set<String> set = new HashSet<>();
                    for (int n = 0; n < options.length(); n++) set.add(options.getString(n));
                    q.values.put(InspectorQuery.Field.valueOf(key), set);
                }
                if (eligible(q)) result.add(q);
            }
            return result;
        } catch (FileNotFoundException missing) { return file.exists() ? null : Collections.emptyList(); }
        catch (IOException | JSONException | IllegalArgumentException invalid) { return null; }
    }
}
