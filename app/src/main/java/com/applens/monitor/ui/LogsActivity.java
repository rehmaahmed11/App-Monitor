package com.applens.monitor.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.applens.monitor.util.ShareProvider;

import com.applens.monitor.R;
import com.applens.monitor.core.Fmt;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.log.ActivityLogWriter;

import java.io.File;
import java.util.List;

/** Browses and shares the plain-text activity records written to /sdcard/AppLens. */
public class LogsActivity extends Activity {

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);
        list = findViewById(R.id.fileList);
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        findViewById(R.id.openFolder).setOnClickListener(v -> openFolder());
        findViewById(R.id.shareLatest).setOnClickListener(v -> shareLatest());
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        list.removeAllViews();
        List<File> files = ActivityLogWriter.listRecords(this);
        if (files.isEmpty()) {
            list.addView(UiKit.emptyState(this, R.drawable.ic_file, "No records yet",
                    "Records are written to /sdcard/AppLens/<package>/activity_*.txt"));
            return;
        }
        for (File file : files) {
            View row = View.inflate(this, R.layout.item_file, null);
            TextView name = row.findViewById(R.id.fileName);
            TextView meta = row.findViewById(R.id.fileMeta);
            name.setText(file.getName());
            meta.setText(Fmt.bytes(file.length()) + " · " + Fmt.ago(file.lastModified())
                    + "\n" + file.getAbsolutePath());
            row.setOnClickListener(v -> share(file));
            list.addView(row);
        }
    }

    private void shareLatest() {
        List<File> files = ActivityLogWriter.listRecords(this);
        if (files.isEmpty()) {
            Toast.makeText(this, "No records to share", Toast.LENGTH_SHORT).show();
            return;
        }
        share(files.get(0));
    }

    private void share(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            Uri uri = ShareProvider.uriFor(this, file);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, file.getName());
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share activity record"));
        } catch (Throwable t) {
            Toast.makeText(this, "Unable to share this file", Toast.LENGTH_SHORT).show();
        }
    }

    private void openFolder() {
        try {
            File base = Environment.getExternalStorageDirectory();
            File dir = new File(base, "AppLens");
            if (!dir.exists() && !dir.mkdirs()) {
                Toast.makeText(this, "Cannot create /sdcard/AppLens", Toast.LENGTH_LONG).show();
                return;
            }
            File target = new File(dir, "README.txt");
            if (!target.exists()) {
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(target)) {
                    fos.write(("AppLens activity records\n"
                            + "Each sub folder holds the plain-text record of one monitoring session.\n")
                            .getBytes("UTF-8"));
                } catch (Throwable ignored) {
                    // the folder is still there for the user to browse manually
                }
            }
            Uri uri = ShareProvider.uriFor(this, target);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "text/plain");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "No file manager available. Path: "
                    + (Environment.getExternalStorageDirectory() + "/AppLens"),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Creates the shared-storage folder even before the first session. */
    public static void ensureFolder() {
        try {
            if (RootShell.get().isRootGranted()) {
                RootShell.get().exec("mkdir -p "
                        + RootShell.shQuote(RootShell.get().externalStoragePath() + "/AppLens"));
                return;
            }
            File dir = new File(Environment.getExternalStorageDirectory(), "AppLens");
            if (!dir.exists()) {
                dir.mkdirs();
            }
        } catch (Throwable ignored) {
            // nothing to do
        }
    }
}
