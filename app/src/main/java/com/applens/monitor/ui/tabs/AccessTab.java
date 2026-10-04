package com.applens.monitor.ui.tabs;

import android.view.View;
import android.widget.LinearLayout;

import com.applens.monitor.R;
import com.applens.monitor.core.Fmt;
import com.applens.monitor.model.DeviceProfile;
import com.applens.monitor.repo.DeviceRepository;
import com.applens.monitor.ui.DetailActivity;
import com.applens.monitor.ui.UiKit;

import java.util.ArrayList;
import java.util.List;

/** ACCESS: device identity, network, location, identifiers, hardware and root state. */
public class AccessTab extends TabPage {

    private LinearLayout container;
    private DeviceProfile profile;
    private boolean loading;

    public AccessTab(DetailActivity host) {
        super(host);
    }

    @Override
    public String title() {
        return "ACCESS";
    }

    @Override
    protected void onBuild() {
        container = body();
        LinearLayout placeholder = UiKit.card(host);
        placeholder.addView(UiKit.text(host, "Reading device profile…", 12, R.color.text_faint));
        container.addView(placeholder);
        load();
    }

    @Override
    public void onRefresh() {
        render();
    }

    private void load() {
        if (loading) {
            return;
        }
        loading = true;
        host.io().execute(() -> {
            DeviceProfile built = DeviceRepository.build(host);
            host.main().post(() -> {
                loading = false;
                profile = built;
                render();
            });
        });
    }

    private void render() {
        if (container == null) {
            return;
        }
        container.removeAllViews();
        if (profile == null) {
            load();
            return;
        }
        List<DeviceProfile.Section> sections = new ArrayList<>(profile.sections);
        for (DeviceProfile.Section section : sections) {
            container.addView(UiKit.section(host, section.title, section.iconRes));
            LinearLayout card = UiKit.card(host);
            for (DeviceProfile.Entry entry : section.entries) {
                if (!entry.hasValue() && entry.note.isEmpty()) {
                    continue;
                }
                int colorRes = entry.colorRes != 0 ? entry.colorRes : R.color.text;
                String value = entry.value;
                if (!entry.note.isEmpty()) {
                    value = value.isEmpty() ? entry.note : value + "  (" + entry.note + ")";
                }
                card.addView(UiKit.keyValue(host, entry.label, Fmt.nz(value, "n/a"), colorRes));
            }
            container.addView(card);
        }
        container.addView(UiKit.text(host,
                "Values come from Build, the framework APIs and, where the platform hides them, "
                        + "from root system calls.", 11, R.color.text_faint));
    }
}
