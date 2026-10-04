package com.applens.monitor.util;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal content provider used to share the plain-text activity records without
 * pulling in AndroidX. It only serves files that already exist on disk.
 */
public class ShareProvider extends ContentProvider {

    public static final String AUTHORITY_SUFFIX = ".share";

    public static Uri uriFor(android.content.Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + AUTHORITY_SUFFIX)
                .appendPath(file.getAbsolutePath())
                .build();
    }

    private File fileFor(Uri uri) {
        if (uri == null || uri.getPathSegments().isEmpty()) {
            return null;
        }
        String path = uri.getPathSegments().get(0);
        if (path == null || !path.startsWith("/")) {
            return null;
        }
        File file = new File(path);
        return file.exists() ? file : null;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = fileFor(uri);
        if (file == null) {
            throw new FileNotFoundException(uri.toString());
        }
        int flags = mode.contains("w")
                ? ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(file, flags);
    }

    @Override
    public String getType(Uri uri) {
        File file = fileFor(uri);
        String name = file == null ? "" : file.getName().toLowerCase(java.util.Locale.US);
        if (name.endsWith(".txt")) {
            return "text/plain";
        }
        if (name.endsWith(".json")) {
            return "application/json";
        }
        if (name.endsWith(".csv")) {
            return "text/csv";
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file = fileFor(uri);
        String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                row[i] = file == null ? "" : file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                row[i] = file == null ? 0L : file.length();
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only provider");
    }
}
