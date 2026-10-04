package com.applens.monitor.ui.tabs;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.applens.monitor.R;
import com.applens.monitor.model.EventItem;
import com.applens.monitor.ui.DetailActivity;
import com.applens.monitor.ui.UiKit;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for the eight monitoring tabs. Handles the scroll / list switch, the
 * optional search bar and the category chip row so subclasses only build content.
 */
public abstract class TabPage {

    protected final DetailActivity host;
    protected final LinearLayout root;
    private LinearLayout scrollBody;
    private ScrollView scroll;
    private ListView listView;
    private BaseAdapter adapter;
    private boolean built;
    private LinearLayout toolbar;
    private TextView searchField;

    protected TabPage(DetailActivity host) {
        this.host = host;
        this.root = new LinearLayout(host);
        this.root.setOrientation(LinearLayout.VERTICAL);
        this.root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public abstract String title();

    /** Static content, built exactly once. */
    protected abstract void onBuild();

    /** Called every time the tab becomes visible or the state ticks. */
    public abstract void onRefresh();

    /** Called when background data (package facts, storage, device) arrives. */
    public void onData() {
        if (built) {
            onRefresh();
        }
    }

    public void onEvent(EventItem event) {
    }

    public View view() {
        return root;
    }

    public final void onShow() {
        if (!built) {
            built = true;
            onBuild();
        }
        onRefresh();
    }

    // ------------------------------------------------------------------
    // Content helpers
    // ------------------------------------------------------------------

    protected LinearLayout body() {
        useScroll();
        return scrollBody;
    }

    /** Replaces the body with a list view bound to {@code adapter}. */
    protected void useList(BaseAdapter newAdapter) {
        this.adapter = newAdapter;
        removeScroll();
        if (listView == null) {
            listView = new ListView(host);
            listView.setDivider(null);
            listView.setDividerHeight(0);
            listView.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
            listView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            listView.setClipToPadding(false);
            listView.setPadding(UiKit.dp(host, 12), UiKit.dp(host, 6),
                    UiKit.dp(host, 12), UiKit.dp(host, 24));
            listView.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        listView.setAdapter(adapter);
        if (listView.getParent() == null) {
            root.addView(listView);
        }
    }

    protected void useScroll() {
        removeList();
        if (scroll == null) {
            scroll = new ScrollView(host);
            scroll.setFillViewport(true);
            scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            scrollBody = new LinearLayout(host);
            scrollBody.setOrientation(LinearLayout.VERTICAL);
            scrollBody.setPadding(UiKit.dp(host, 14), UiKit.dp(host, 6),
                    UiKit.dp(host, 14), UiKit.dp(host, 26));
            scroll.addView(scrollBody, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            scroll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        if (scroll.getParent() == null) {
            root.addView(scroll);
        }
    }

    private void removeScroll() {
        if (scroll != null && scroll.getParent() != null) {
            ((ViewGroup) scroll.getParent()).removeView(scroll);
        }
    }

    private void removeList() {
        if (listView != null && listView.getParent() != null) {
            ((ViewGroup) listView.getParent()).removeView(listView);
        }
    }

    /** Adds a search field above the content. */
    protected void addSearch(String hint, Runnable onChanged) {
        LinearLayout bar = UiKit.row(host);
        bar.setPadding(UiKit.dp(host, 14), UiKit.dp(host, 8), UiKit.dp(host, 14), 0);

        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackgroundResource(R.drawable.bg_search);
        box.setPadding(UiKit.dp(host, 12), 0, UiKit.dp(host, 12), 0);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(0,
                UiKit.dp(host, 42), 1f);
        bar.addView(box, boxLp);

        ImageView icon = new ImageView(host);
        icon.setImageResource(R.drawable.ic_search);
        icon.setColorFilter(UiKit.color(host, R.color.text_faint));
        box.addView(icon, new LinearLayout.LayoutParams(UiKit.dp(host, 17), UiKit.dp(host, 17)));

        EditText input = new EditText(host);
        input.setHint(hint);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        input.setTextColor(UiKit.color(host, R.color.text));
        input.setHintTextColor(UiKit.color(host, R.color.text_faint));
        input.setBackground(null);
        input.setSingleLine(true);
        input.setPadding(UiKit.dp(host, 10), 0, 0, 0);
        input.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        box.addView(input);
        searchField = input;

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (onChanged != null) {
                    onChanged.run();
                }
            }
        });

        if (toolbar == null) {
            toolbar = new LinearLayout(host);
            toolbar.setOrientation(LinearLayout.VERTICAL);
            toolbar.addView(bar);
        }
        if (toolbar.getParent() == null) {
            root.addView(toolbar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    protected String query() {
        if (searchField == null) {
            return "";
        }
        CharSequence text = searchField.getText();
        return text == null ? "" : text.toString().trim();
    }

    /** Adds a horizontally scrollable chip row to the toolbar. */
    protected void addChips(String[] labels, int[] active, java.util.function.IntConsumer listener) {
        if (toolbar == null) {
            toolbar = new LinearLayout(host);
            toolbar.setOrientation(LinearLayout.VERTICAL);
        }
        HorizontalScrollView scroller = new HorizontalScrollView(host);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setPadding(UiKit.dp(host, 14), UiKit.dp(host, 6), UiKit.dp(host, 14), 0);
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView chip = UiKit.chip(host, labels[i], active != null && i < active.length && active[i] == 1);
            chip.setOnClickListener(v -> {
                if (listener != null) {
                    listener.accept(index);
                }
            });
            row.addView(chip);
        }
        scroller.addView(row);
        if (toolbar.getParent() == null) {
            root.addView(toolbar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        toolbar.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    protected void showEmpty(String title, String subtitle, int iconRes) {
        if (root.getChildCount() == 0) {
            root.addView(UiKit.emptyState(host, iconRes, title, subtitle));
        }
    }

    protected static List<Row> rows() {
        return new ArrayList<>();
    }

    protected LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    protected FrameLayout frame() {
        return new FrameLayout(host);
    }
}
