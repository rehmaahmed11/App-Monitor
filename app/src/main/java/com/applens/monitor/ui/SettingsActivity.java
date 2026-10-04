package com.applens.monitor.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.applens.monitor.R;
import com.applens.monitor.core.LogSettings;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.log.ActivityLogWriter;
import com.applens.monitor.monitor.MonitorHub;
import com.applens.monitor.monitor.MonitorState;
import com.applens.monitor.net.DnsVpnService;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Everything the user can change about the plain-text report: where it is written
 * (including a folder picker), what it contains, and whether DNS is captured.
 *
 * <p>The screen never just remembers a path — it verifies it. "CREATE &amp; TEST"
 * creates the folder and writes a probe file, so a path that root cannot write to
 * (or that Android will not grant access to) is reported here instead of silently
 * producing an empty report later.</p>
 */
public class SettingsActivity extends Activity {

    private static final int REQ_TREE = 4711;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "applens-settings");
        t.setDaemon(true);
        return t;
    });

    private EditText pathInput;
    private TextView pathText;
    private TextView pathPreview;
    private TextView pathStatus;
    private TextView vpnStatus;
    private TextView recordsText;
    private LinearLayout presetRow;
    private LinearLayout heartbeatRow;
    private Switch switchReport;
    private Switch switchPerApp;
    private Switch switchFull;
    private Switch switchVpn;

    private final int[] heartbeatChoices = {0, 1, 5, 15, 30};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        pathInput = findViewById(R.id.pathInput);
        pathText = findViewById(R.id.pathText);
        pathPreview = findViewById(R.id.pathPreview);
        pathStatus = findViewById(R.id.pathStatus);
        vpnStatus = findViewById(R.id.vpnStatus);
        recordsText = findViewById(R.id.recordsText);
        presetRow = findViewById(R.id.presetRow);
        heartbeatRow = findViewById(R.id.heartbeatRow);
        switchReport = findViewById(R.id.switchReport);
        switchPerApp = findViewById(R.id.switchPerApp);
        switchFull = findViewById(R.id.switchFull);
        switchVpn = findViewById(R.id.switchVpn);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        findViewById(R.id.saveButton).setOnClickListener(v -> save());
        findViewById(R.id.testButton).setOnClickListener(v -> saveAndTest());
        findViewById(R.id.browseButton).setOnClickListener(v -> browse());
        findViewById(R.id.openRecords).setOnClickListener(v ->
                startActivity(new Intent(this, LogsActivity.class)));

        switchReport.setOnCheckedChangeListener((button, checked) -> {
            LogSettings.setReportEnabled(checked);
            refresh();
        });
        switchPerApp.setOnCheckedChangeListener((button, checked) -> {
            LogSettings.setFolderPerApp(checked);
            refresh();
        });
        switchFull.setOnCheckedChangeListener((button, checked) -> {
            LogSettings.setFullReport(checked);
            refresh();
        });
        switchVpn.setOnCheckedChangeListener((button, checked) -> {
            LogSettings.setDnsCapture(checked);
            refresh();
        });

        buildPresets();
        buildHeartbeatChoices();
        refresh();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void refresh() {
        String folder = LogSettings.folder();
        pathText.setText(folder);
        pathInput.setText(folder);
        pathPreview.setText("A session for com.example.app writes:\n"
                + previewPath(folder));
        switchReport.setChecked(LogSettings.reportEnabled());
        switchPerApp.setChecked(LogSettings.folderPerApp());
        switchFull.setChecked(LogSettings.fullReport());
        switchVpn.setChecked(LogSettings.dnsCapture());
        buildHeartbeatChoices();

        String vpn = "DNS capture is " + DnsVpnService.statusText()
                + (DnsVpnService.detailText().isEmpty() ? "" : " · " + DnsVpnService.detailText());
        if (!DnsVpnService.errorText().isEmpty()) {
            vpn = vpn + "\n" + DnsVpnService.errorText();
        }
        vpnStatus.setText(vpn);

        io.execute(() -> {
            List<File> records = ActivityLogWriter.listRecords(this);
            String status = ActivityLogWriter.get().status();
            String summary = records.isEmpty()
                    ? "No record file has been written yet."
                    : records.size() + " record file(s) found, newest: " + records.get(0).getName()
                    + "\n" + records.get(0).getAbsolutePath();
            if (status != null && !status.isEmpty()) {
                summary = summary + "\nWriter: " + status;
            }
            String text = summary;
            main.post(() -> recordsText.setText(text));
        });
    }

    private String previewPath(String folder) {
        if (!LogSettings.folderPerApp()) {
            return folder + "/activity_<date>_<time>.txt";
        }
        return folder + "/com.example.app/activity_<date>_<time>.txt";
    }

    private void buildPresets() {
        presetRow.removeAllViews();
        for (String preset : LogSettings.SUGGESTED_FOLDERS) {
            TextView chip = UiKit.chip(this, preset, preset.equals(LogSettings.folder()));
            chip.setOnClickListener(v -> {
                pathInput.setText(preset);
                save();
            });
            presetRow.addView(chip);
        }
        TextView custom = UiKit.chip(this, "/sdcard/Android/data/" + getPackageName()
                + "/files/AppLens", false);
        custom.setOnClickListener(v -> {
            pathInput.setText("/sdcard/Android/data/" + getPackageName() + "/files/AppLens");
            save();
        });
        presetRow.addView(custom);
    }

    private void buildHeartbeatChoices() {
        heartbeatRow.removeAllViews();
        int current = LogSettings.heartbeatMinutes();
        for (int minutes : heartbeatChoices) {
            String label = minutes == 0 ? "off" : minutes + " min";
            TextView chip = UiKit.chip(this, label, minutes == current);
            chip.setOnClickListener(v -> {
                LogSettings.setHeartbeatMinutes(minutes);
                buildHeartbeatChoices();
            });
            heartbeatRow.addView(chip);
        }
    }

    // ------------------------------------------------------------------
    // Folder handling
    // ------------------------------------------------------------------

    private void save() {
        String typed = pathInput.getText() == null ? "" : pathInput.getText().toString();
        LogSettings.setFolder(typed);
        refresh();
        Toast.makeText(this, "Output folder: " + LogSettings.folder(), Toast.LENGTH_SHORT).show();
    }

    private void saveAndTest() {
        save();
        final String folder = LogSettings.folder();
        pathStatus.setText("Creating and testing " + folder + " …");
        io.execute(() -> {
            LogSettings.Check result = LogSettings.ensureWritable(this, folder);
            boolean root = RootShell.get().isRootGranted();
            String message;
            if (result.ok) {
                message = "✓ " + result.path + " is writable (" + result.note + ")."
                        + " Reports will be written there.";
            } else {
                message = "✗ " + result.path + " could not be written: " + result.note
                        + (root ? "" : ". Root is not granted — grant it on the dashboard, or pick"
                        + " the app's own folder from the presets above.");
            }
            main.post(() -> {
                pathStatus.setText(message);
                Toast.makeText(this, result.ok ? "Folder ready" : "Folder not writable",
                        Toast.LENGTH_LONG).show();
            });
            refresh();
        });
    }

    private void browse() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent, REQ_TREE);
        } catch (Throwable error) {
            Toast.makeText(this, "No folder picker on this device — type the path instead",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TREE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        String resolved = uri == null ? null : LogSettings.fromTreeUri(uri.toString());
        if (resolved == null) {
            Toast.makeText(this, "That folder is not on shared storage — type its path",
                    Toast.LENGTH_LONG).show();
            return;
        }
        pathInput.setText(resolved);
        saveAndTest();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // The dashboard's session state is shown here too, so the folder can be
        // checked while a session is running.
        MonitorState state = MonitorHub.get().stateSnapshot();
        if (state.live() && !state.recordPath.isEmpty()) {
            pathStatus.setText("Current session record: " + state.recordPath
                    + (state.recordNote.isEmpty() ? "" : " (" + state.recordNote + ")"));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
