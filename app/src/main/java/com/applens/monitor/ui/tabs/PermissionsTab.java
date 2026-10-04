package com.applens.monitor.ui.tabs;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.applens.monitor.R;
import com.applens.monitor.core.RootShell;
import com.applens.monitor.log.ActivityLogWriter;
import com.applens.monitor.model.AppFacts;
import com.applens.monitor.model.PermissionItem;
import com.applens.monitor.ui.DetailActivity;
import com.applens.monitor.ui.UiKit;

import java.util.List;
import java.util.Locale;

/** PERMISSIONS: every permission grouped by capability with grant state and AppOps. */
public class PermissionsTab extends TabPage {

    private LinearLayout container;
    private boolean grantedAll;

    public PermissionsTab(DetailActivity host) {
        super(host);
    }

    @Override
    public String title() {
        return "PERMISSIONS";
    }

    @Override
    protected void onBuild() {
        container = body();
    }

    @Override
    public void onRefresh() {
        if (container == null) {
            return;
        }
        container.removeAllViews();
        AppFacts facts = host.facts();
        if (facts == null || facts.permissions.isEmpty()) {
            container.addView(UiKit.emptyState(host, R.drawable.ic_lock, "No permission data",
                    "Grant root so AppLens can read the package database"));
            return;
        }

        LinearLayout summary = UiKit.card(host);
        summary.addView(UiKit.text(host, "SUMMARY", 10, R.color.muted));
        summary.addView(UiKit.keyValue(host, "Requested",
                String.valueOf(facts.permissions.size()), R.color.text));
        summary.addView(UiKit.keyValue(host, "Granted",
                facts.grantedCount() + " of " + facts.permissions.size(),
                facts.grantedCount() > 0 ? R.color.ok : R.color.danger));
        summary.addView(UiKit.keyValue(host, "Dangerous granted",
                facts.dangerousGrantedCount() + " of " + facts.dangerousCount(),
                facts.dangerousGrantedCount() > 0 ? R.color.warn : R.color.text_dim));
        summary.addView(UiKit.keyValue(host, "Source",
                facts.dumpsysAvailable.isEmpty() ? "PackageManager" : "dumpsys package (root)",
                R.color.text_dim));
        LinearLayout actions = UiKit.row(host);
        TextView grant = UiKit.button(host,
                grantedAll ? "GRANT ALL (AGAIN)" : "GRANT ALL", R.drawable.bg_button_ghost);
        grant.setOnClickListener(v -> grantAll());
        actions.addView(grant, new LinearLayout.LayoutParams(0, UiKit.dp(host, 40), 1f));
        TextView reset = UiKit.button(host, "RESET", R.drawable.bg_button_ghost);
        reset.setOnClickListener(v -> resetAll());
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(0, UiKit.dp(host, 40), 1f);
        resetLp.leftMargin = UiKit.dp(host, 8);
        actions.addView(reset, resetLp);
        summary.addView(actions);
        container.addView(summary);

        List<String> groups = facts.presentGroups();
        for (String group : groups) {
            List<PermissionItem> items = facts.byGroup(group);
            int granted = 0;
            for (PermissionItem item : items) {
                if (item.granted) {
                    granted++;
                }
            }
            container.addView(UiKit.section(host,
                    PermissionItem.groupLabel(group) + "  (" + granted + "/" + items.size() + ")",
                    iconFor(group)));
            LinearLayout card = UiKit.card(host);
            card.setPadding(UiKit.dp(host, 12), UiKit.dp(host, 8), UiKit.dp(host, 12), UiKit.dp(host, 8));
            for (PermissionItem item : items) {
                card.addView(permissionRow(item));
            }
            container.addView(card);
        }
    }

    private View permissionRow(PermissionItem item) {
        View view = View.inflate(host, R.layout.item_permission, null);
        TextView mark = view.findViewById(R.id.permMark);
        TextView name = view.findViewById(R.id.permName);
        TextView detail = view.findViewById(R.id.permDetail);
        TextView state = view.findViewById(R.id.permState);

        mark.setText(item.granted ? "✓" : "✕");
        mark.setTextColor(UiKit.color(host, item.granted ? R.color.ok : R.color.muted_2));
        name.setText(PermissionItem.shortName(item.name));
        StringBuilder extra = new StringBuilder();
        extra.append(item.protection);
        if (item.installTime) {
            extra.append(" · install-time");
        }
        if (!item.flags.isEmpty()) {
            extra.append(" · ").append(item.flags);
        }
        if (!item.appOp.isEmpty()) {
            extra.append(" · appop ").append(item.appOp).append(": ").append(item.appOpMode);
        }
        detail.setText(extra.toString());
        state.setText(item.granted ? "GRANTED" : "DENIED");
        state.setTextColor(UiKit.color(host, item.granted ? R.color.ok : R.color.muted_2));
        state.setBackground(UiKit.pill(
                UiKit.color(host, item.granted ? R.color.ok_dim : R.color.surface_high),
                UiKit.dp(host, 8)));
        state.setPadding(UiKit.dp(host, 8), UiKit.dp(host, 3), UiKit.dp(host, 8), UiKit.dp(host, 3));
        name.setContentDescription(item.name);
        return view;
    }

    private static int iconFor(String group) {
        switch (group) {
            case "LOCATION":
                return R.drawable.ic_gps;
            case "CAMERA":
                return R.drawable.ic_camera;
            case "AUDIO":
                return R.drawable.ic_mic;
            case "CONTACTS":
                return R.drawable.ic_contacts;
            case "PHONE":
                return R.drawable.ic_phone;
            case "STORAGE":
                return R.drawable.ic_image;
            case "SMS":
                return R.drawable.ic_bell;
            case "NEARBY":
                return R.drawable.ic_network;
            case "SENSORS":
                return R.drawable.ic_pulse;
            case "USAGE":
                return R.drawable.ic_apps;
            default:
                return R.drawable.ic_shield;
        }
    }

    private void grantAll() {
        Toast.makeText(host, "Granting runtime permissions…", Toast.LENGTH_SHORT).show();
        host.io().execute(() -> {
            boolean ok = RootShell.get().grantAllRuntimePermissions(host.pkg());
            host.main().post(() -> {
                grantedAll = true;
                ActivityLogWriter.get().writeRaw(com.applens.monitor.core.Fmt.clock(
                        System.currentTimeMillis()) + "  [ACTION] grant all runtime permissions "
                        + host.pkg() + " -> " + ok);
                Toast.makeText(host, ok ? "Permissions granted" : "Nothing to grant",
                        Toast.LENGTH_SHORT).show();
                onRefresh();
            });
        });
    }

    private void resetAll() {
        host.io().execute(() -> {
            RootShell.get().resetRuntimePermissions(host.pkg());
            host.main().post(() -> {
                ActivityLogWriter.get().writeRaw(com.applens.monitor.core.Fmt.clock(
                        System.currentTimeMillis()) + "  [ACTION] reset runtime permissions "
                        + host.pkg());
                onRefresh();
            });
        });
    }
}
