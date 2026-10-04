package com.applens.monitor.ui.tabs;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.applens.monitor.R;
import com.applens.monitor.ui.UiKit;

import java.util.ArrayList;
import java.util.List;

/** A generic list row shared by the activity, network, DNS, file and process lists. */
public class Row {

    public int iconRes = R.drawable.ic_info;
    public int colorRes = R.color.text_dim;
    public String title = "";
    public String subtitle = "";
    public String time = "";
    public String meta = "";
    public String badge = "";
    public int badgeColorRes = R.color.muted;

    public Row() {
    }

    public Row(int iconRes, int colorRes, String title, String subtitle, String time, String meta) {
        this.iconRes = iconRes;
        this.colorRes = colorRes;
        this.title = title;
        this.subtitle = subtitle;
        this.time = time;
        this.meta = meta;
    }

    public static final class Holder {
        public final List<Row> rows = new ArrayList<>();
    }

    public static class Adapter extends BaseAdapter {
        private final Context context;
        private final LayoutInflater inflater;
        private final List<Row> rows = new ArrayList<>();

        public Adapter(Context context) {
            this.context = context;
            this.inflater = LayoutInflater.from(context);
        }

        public void setRows(List<Row> newRows) {
            rows.clear();
            if (newRows != null) {
                rows.addAll(newRows);
            }
            notifyDataSetChanged();
        }

        public List<Row> rows() {
            return rows;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Row getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = inflater.inflate(R.layout.item_row, parent, false);
            }
            Row row = getItem(position);
            ImageView icon = view.findViewById(R.id.rowIcon);
            TextView title = view.findViewById(R.id.rowTitle);
            TextView subtitle = view.findViewById(R.id.rowSubtitle);
            TextView time = view.findViewById(R.id.rowTime);
            TextView meta = view.findViewById(R.id.rowMeta);

            icon.setImageResource(row.iconRes);
            icon.setColorFilter(UiKit.color(context, row.colorRes));
            title.setText(row.title);
            subtitle.setText(row.subtitle);
            subtitle.setVisibility(row.subtitle == null || row.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
            if (row.time != null && row.time.isEmpty()) {
                time.setVisibility(View.GONE);
            } else {
                time.setVisibility(View.VISIBLE);
                time.setText(row.time);
            }
            meta.setText(row.meta);
            meta.setTextColor(UiKit.color(context, row.badgeColorRes));
            return view;
        }
    }
}
