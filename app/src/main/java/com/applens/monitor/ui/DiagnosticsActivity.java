package com.applens.monitor.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.applens.monitor.R;
import com.applens.monitor.log.DiagnosticLog;

/** Shows local crash/error reports in a selectable box with copy and clear actions. */
public class DiagnosticsActivity extends Activity {

    public static final String EXTRA_RECOVERED_REPORT = "recovered_report";

    private ScrollView reportScroll;
    private TextView reportText;
    private TextView reportMeta;
    private TextView copyButton;
    private long renderedSize = -1L;
    private long renderedModified = -1L;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshReports = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (DiagnosticLog.sizeBytes() != renderedSize
                    || DiagnosticLog.lastModified() != renderedModified) {
                render();
            }
            refreshHandler.postDelayed(this, 1500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);

        reportScroll = findViewById(R.id.diagnosticScroll);
        reportText = findViewById(R.id.diagnosticText);
        reportMeta = findViewById(R.id.diagnosticMeta);
        copyButton = findViewById(R.id.copyDiagnostics);
        View recentIssue = findViewById(R.id.recentIssueBanner);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        copyButton.setOnClickListener(v -> copyAll());
        findViewById(R.id.clearDiagnostics).setOnClickListener(v -> confirmClear());
        recentIssue.setVisibility(getIntent().getBooleanExtra(EXTRA_RECOVERED_REPORT, false)
                ? View.VISIBLE : View.GONE);

        // Visiting the report is enough to dismiss its one-time startup notice.
        DiagnosticLog.dismissPendingReport();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        refreshHandler.removeCallbacks(refreshReports);
        refreshHandler.postDelayed(refreshReports, 1500L);
    }

    @Override
    protected void onPause() {
        refreshHandler.removeCallbacks(refreshReports);
        super.onPause();
    }

    private void render() {
        renderedSize = DiagnosticLog.sizeBytes();
        renderedModified = DiagnosticLog.lastModified();
        String report = DiagnosticLog.readAll();
        if (report.trim().isEmpty()) {
            reportText.setText("No diagnostics have been saved yet.\n\n"
                    + "If AppLens crashes or detects an unexpected error, reopen the app "
                    + "and the report will appear here.");
            reportMeta.setText("NO REPORTS · SAVED ON THIS DEVICE ONLY");
            copyButton.setEnabled(false);
            copyButton.setAlpha(0.55f);
            return;
        }
        reportText.setText(report);
        reportScroll.post(() -> reportScroll.fullScroll(View.FOCUS_DOWN));
        reportMeta.setText(countEntries(report) + " REPORTS · "
                + Math.max(1, (DiagnosticLog.sizeBytes() + 1023) / 1024)
                + " KB · SAVED ON THIS DEVICE ONLY");
        copyButton.setEnabled(true);
        copyButton.setAlpha(1f);
    }

    private int countEntries(String report) {
        int count = 0;
        String marker = "==================== ";
        int from = 0;
        while (true) {
            int found = report.indexOf(marker, from);
            if (found < 0) {
                return count;
            }
            count++;
            from = found + marker.length();
        }
    }

    private void copyAll() {
        render();
        String report = DiagnosticLog.readAll();
        if (report.trim().isEmpty()) {
            Toast.makeText(this, "No diagnostics to copy", Toast.LENGTH_SHORT).show();
            render();
            return;
        }
        try {
            ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                throw new IllegalStateException("Clipboard is unavailable");
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("AppLens diagnostics", report));
            Toast.makeText(this, "Diagnostics copied. Paste them into your message.",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable error) {
            Toast.makeText(this, "Could not copy diagnostics: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this, R.style.AppTheme_Dialog)
                .setTitle("Clear diagnostics?")
                .setMessage("This permanently deletes all saved crash reports and error details.")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("CLEAR", (dialog, which) -> {
                    if (DiagnosticLog.clear()) {
                        render();
                        Toast.makeText(this, "Diagnostics cleared", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Could not clear diagnostics",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }
}
