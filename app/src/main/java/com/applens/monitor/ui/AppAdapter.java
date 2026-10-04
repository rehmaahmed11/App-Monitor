package com.applens.monitor.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.applens.monitor.R;
import com.applens.monitor.core.Fmt;
import com.applens.monitor.model.AppItem;

import java.util.ArrayList;
import java.util.List;

/** List adapter for the application scanner. */
public class AppAdapter extends BaseAdapter {

    private final Context context;
    private final LayoutInflater inflater;
    private final List<AppItem> items = new ArrayList<>();

    public AppAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    public void setItems(List<AppItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    public List<AppItem> items() {
        return items;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public AppItem getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.item_app, parent, false);
        }
        final AppItem item = getItem(position);
        ImageView icon = view.findViewById(R.id.icon);
        TextView name = view.findViewById(R.id.name);
        TextView pkg = view.findViewById(R.id.packageName);
        TextView meta = view.findViewById(R.id.meta);
        TextView badge = view.findViewById(R.id.badge);

        Drawable drawable = item.icon(context);
        if (drawable != null) {
            icon.setImageDrawable(drawable);
        } else {
            icon.setImageResource(R.drawable.ic_apps);
        }
        name.setText(item.displayLabel());
        pkg.setText(item.pkg);

        StringBuilder metaText = new StringBuilder();
        metaText.append('v').append(Fmt.nz(item.versionName, "?"));
        metaText.append(" · ").append(item.sizeText());
        if (item.firstInstall > 0) {
            metaText.append(" · installed ").append(Fmt.date(item.firstInstall));
        }
        meta.setText(metaText.toString());

        if (item.running) {
            badge.setText("RUNNING");
            badge.setTextColor(UiKit.color(context, R.color.ok));
            badge.setBackground(UiKit.pill(UiKit.color(context, R.color.ok_dim), UiKit.dp(context, 8)));
        } else if (item.system) {
            badge.setText("SYSTEM");
            badge.setTextColor(UiKit.color(context, R.color.muted));
            badge.setBackground(UiKit.pill(UiKit.color(context, R.color.surface_high), UiKit.dp(context, 8)));
        } else {
            badge.setText("USER");
            badge.setTextColor(UiKit.color(context, R.color.info));
            badge.setBackground(UiKit.pill(UiKit.color(context, R.color.info_dim), UiKit.dp(context, 8)));
        }
        badge.setPadding(UiKit.dp(context, 8), UiKit.dp(context, 4),
                UiKit.dp(context, 8), UiKit.dp(context, 4));
        return view;
    }
}
