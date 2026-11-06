package com.sandrox.osservio.export;

import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.core.content.FileProvider;
import java.io.FileNotFoundException;
import java.util.regex.Pattern;

/** Manifest component for completed exports only. Canonical path containment is owned by AndroidX. */
public final class ExportFileProvider extends FileProvider {
    private static final Pattern COMPLETED = Pattern.compile("osservio-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.zip");
    public ExportFileProvider() { super(R.xml.osservio_export_paths); }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        requireCompleted(uri);
        return super.query(uri, projection, selection, selectionArgs, sortOrder);
    }
    @Override public String getType(Uri uri) { requireCompleted(uri); return super.getType(uri); }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        requireCompleted(uri);
        if (!"r".equals(mode)) throw new SecurityException("Exports are read-only");
        return super.openFile(uri, mode);
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new SecurityException("Exports are read-only");
    }
    private static void requireCompleted(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || !COMPLETED.matcher(name).matches()) throw new SecurityException("Not a completed export");
    }
}
