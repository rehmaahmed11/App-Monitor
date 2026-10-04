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
import com.applens.monitor.core.LogSettings;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.log.ActivityLogWriter;

import java.io.File;
import java.util.List;

/** Browses and shares the plain-text activity records written to /sdcard/AppLens. */
public class LogsActivity extends Activity {

    private LinearLayout list;
    private TextView folderText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);
        list = findViewById(R.id.fileList);
        folderText = findViewById(R.id.logsFolderText);
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        findViewById(R.id.openFolder).setOnClickListener(v -> openFolder());
        findViewById(R.id.shareLatest).setOnClickListener(v -> shareLatest());
        View settings = findViewById(R.id.logsSettings);
        if (settings != null) {
            settings.setOnClickListener(v ->
                    startActivity(new Intent(this, SettingsActivity.class)));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        list.removeAllViews();
        if (folderText != null) {
            folderText.setText("Records are plain text in " + LogSettings.folder()
                    + (LogSettings.folderPerApp() ? "/<package>/" : "/")
                    + "activity_*.txt");
        }
        List<File> files = ActivityLogWriter.listRecords(this);
        if (files.isEmpty()) {
            list.addView(UiKit.emptyState(this, R.drawable.ic_file, "No records yet",
                    "Records are written to " + LogSettings.folder()
                            + (LogSettings.folderPerApp() ? "/<package>" : "")
                            + "/activity_*.txt\n\nChange the folder with SETTINGS on"
                            + " the previous screen."));
            return;
        }
        String note = "Folder: " + LogSettings.folder()
                + (LogSettings.folderPerApp() ? " (one folder per app)" : "")
                + " · " + files.size() + " file(s)" + writerNote();
        list.addView(UiKit.emptyState(this, R.drawable.ic_file, "Saved records", note));
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

    private String writerNote() {
        String status = ActivityLogWriter.get().status();
        if (status == null || status.isEmpty()) {
            return "";
        }
        return "\nWriter: " + status;
    }

    private void share(File file) {
        try {
            // Files written through root may not be readable by this process; the
            // writer copies such a record into the app's own folder first.
            File shareable = ActivityLogWriter.readableForShare(this, file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            Uri uri = ShareProvider.uriFor(this, shareable);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, shareable.getName());
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share activity record"));
        } catch (Throwable t) {
            Toast.makeText(this, "Unable to share this file", Toast.LENGTH_SHORT).show();
        }
    }

    private void openFolder() {
        if (!RootShell.get().isRootGranted()
                && android.os.Build.VERSION.SDK_INT >= 30
                && !Environment.isExternalStorageManager()) {
            try {
                Intent intent = new Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            } catch (Throwable ignored) {
                // fall through to the folder view
            }
        }
        try {
            String folder = LogSettings.folder();
            File dir = new File(folder);
            if (!dir.exists() && !dir.mkdirs()) {
                if (!RootShell.get().isRootGranted()
                        || !RootShell.get().makeFolder(folder).ok) {
                    Toast.makeText(this, "Cannot create " + folder, Toast.LENGTH_LONG).show();
                    return;
                }
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
            String folder = LogSettings.folder();
            if (RootShell.get().isRootGranted()) {
                RootShell.get().makeFolder(folder);
                return;
            }
            File dir = new File(folder);
            if (!dir.exists()) {
                dir.mkdirs();
            }
        } catch (Throwable ignored) {
            // nothing to do
        }
    }
}
