package com.applens.monitor.ui.tabs;

import android.view.View;
import android.widget.LinearLayout;

import com.applens.monitor.R;
import com.applens.monitor.core.Fmt;
import com.applens.monitor.model.AppItem;
import com.applens.monitor.model.StorageItem;
import com.applens.monitor.repo.StorageRepository;
import com.applens.monitor.ui.DetailActivity;
import com.applens.monitor.ui.UiKit;
import com.applens.monitor.ui.widget.RingView;

import java.util.ArrayList;
import java.util.List;

/** DATA: storage breakdown, databases, files and the APK itself. */
public class DataTab extends TabPage {

    private LinearLayout container;
    private RingView ring;
    private LinearLayout legend;
    private LinearLayout summaryCard;
    private boolean loading;

    public DataTab(DetailActivity host) {
        super(host);
    }

    @Override
    public String title() {
        return "DATA";
    }

    @Override
    protected void onBuild() {
        container = body();
        renderSkeleton();
    }

    @Override
    public void onRefresh() {
        if (container == null || loading) {
            return;
        }
        AppItem app = host.app();
        String dataDir = app != null ? app.dataDir : "/data/user/0/" + host.pkg();
        if (app == null && host.facts() != null && !host.facts().dataDir.isEmpty()) {
            dataDir = host.facts().dataDir;
        }
        loading = true;
        final String dir = dataDir;
        host.io().execute(() -> {
            StorageRepository.Breakdown breakdown = StorageRepository.breakdown(host.pkg(), dir);
            int libs = StorageRepository.nativeLibraryCount(host.pkg());
            host.main().post(() -> {
                loading = false;
                render(breakdown, libs);
            });
        });
    }

    private void renderSkeleton() {
        container.removeAllViews();
        summaryCard = UiKit.card(host);
        summaryCard.addView(UiKit.text(host, "Measuring storage…", 12, R.color.text_faint));
        container.addView(summaryCard);
    }

    private void render(StorageRepository.Breakdown breakdown, int nativeLibs) {
        container.removeAllViews();

        summaryCard = UiKit.card(host);
        summaryCard.addView(UiKit.text(host, "ON-DEVICE DATA", 10, R.color.muted));

        LinearLayout chartRow = UiKit.row(host);
        ring = new RingView(host);
        LinearLayout.LayoutParams ringLp = new LinearLayout.LayoutParams(
                UiKit.dp(host, 132), UiKit.dp(host, 132));
        chartRow.addView(ring, ringLp);
        legend = new LinearLayout(host);
        legend.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams legendLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        legendLp.leftMargin = UiKit.dp(host, 12);
        chartRow.addView(legend, legendLp);
        summaryCard.addView(chartRow);

        List<RingView.Slice> slices = new ArrayList<>();
        int index = 0;
        for (StorageItem item : breakdown.items) {
            if (item.bytes <= 0) {
                continue;
            }
            slices.add(new RingView.Slice(item.name, item.bytes,
                    UiKit.SERIES[index % UiKit.SERIES.length]));
            LinearLayout line = UiKit.row(host);
            View dot = new View(host);
            dot.setBackground(UiKit.rounded(UiKit.SERIES[index % UiKit.SERIES.length], 0,
                    UiKit.dp(host, 3)));
            line.addView(dot, new LinearLayout.LayoutParams(UiKit.dp(host, 8), UiKit.dp(host, 8)));
            android.widget.TextView name = UiKit.text(host,
                    item.name + "  " + Fmt.bytes(item.bytes), 11, R.color.text_dim);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            nameLp.leftMargin = UiKit.dp(host, 8);
            nameLp.bottomMargin = UiKit.dp(host, 4);
            line.addView(name, nameLp);
            legend.addView(line);
            index++;
        }
        ring.setSlices(slices, Fmt.bytes(breakdown.total), breakdown.items.size() + " locations");
        legend.addView(UiKit.keyValue(host, "APK",
                Fmt.bytes(appApkSize()), R.color.accent));
        legend.addView(UiKit.keyValue(host, "Private data",
                breakdown.dataDirBytes < 0 ? "n/a" : Fmt.bytes(breakdown.dataDirBytes),
                R.color.text));
        container.addView(summaryCard);

        LinearLayout facts = UiKit.card(host);
        facts.addView(UiKit.text(host, "FILES & DATABASES", 10, R.color.muted));
        facts.addView(UiKit.keyValue(host, "Total files",
                breakdown.fileCount < 0 ? "n/a" : String.valueOf(breakdown.fileCount), R.color.text));
        facts.addView(UiKit.keyValue(host, "Database files",
                breakdown.databaseCount < 0 ? "n/a" : String.valueOf(breakdown.databaseCount),
                R.color.text));
        facts.addView(UiKit.keyValue(host, "Cache size",
                breakdown.cacheSizeBytes <= 0 ? "none" : Fmt.bytes(breakdown.cacheSizeBytes),
                R.color.warn));
        facts.addView(UiKit.keyValue(host, "Largest file",
                breakdown.largestFile.isEmpty() ? "n/a"
                        : Fmt.bytes(breakdown.largestFileBytes) + "  " + breakdown.largestFile,
                R.color.text_dim));
        facts.addView(UiKit.keyValue(host, "Native libraries", String.valueOf(nativeLibs),
                R.color.text_dim));
        facts.addView(UiKit.keyValue(host, "Data directory", breakdown.dataDir, R.color.text_dim));
        container.addView(facts);

        if (!breakdown.items.isEmpty()) {
            container.addView(UiKit.section(host, "LOCATIONS", R.drawable.ic_folder));
            LinearLayout locations = UiKit.card(host);
            for (StorageItem item : breakdown.items) {
                locations.addView(UiKit.keyValue(host, item.name, Fmt.bytes(item.bytes), R.color.text));
                locations.addView(UiKit.keyValue(host, "  " + item.path, "", R.color.text_faint));
            }
            container.addView(locations);
        }
    }

    private long appApkSize() {
        AppItem app = host.app();
        if (app != null && app.apkSize > 0) {
            return app.apkSize;
        }
        if (host.facts() != null) {
            return StorageRepository.apkBytes(host.facts().sourceDir);
        }
        return StorageRepository.apkBytes(app != null ? app.sourceDir : "");
    }
}
