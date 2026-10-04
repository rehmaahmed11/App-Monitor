package com.applens.monitor.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.applens.monitor.R;
import com.applens.monitor.log.DiagnosticLog;

/** Small programmatic view factory that keeps every tab visually consistent. */
public final class UiKit {

    public static final int[] SERIES = {
            Color.rgb(0x22, 0xD3, 0xEE),
            Color.rgb(0x8B, 0x5C, 0xF6),
            Color.rgb(0x34, 0xD3, 0x99),
            Color.rgb(0xFB, 0xBF, 0x24),
            Color.rgb(0xF8, 0x71, 0x71),
            Color.rgb(0x60, 0xA5, 0xFA),
    };

    private UiKit() {
    }

    public static int dp(Context ctx, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics());
    }

    /**
     * Resolves a colour, accepting either a colour resource id (the documented contract) or a
     * raw ARGB literal. The literal form used to be indistinguishable from a resource id here,
     * so a stray {@code 0xFF04121A} resolved through {@code Resources.getColor()} threw
     * {@link android.content.res.Resources.NotFoundException} and killed the whole activity.
     * Callers should still prefer colour resources, so that themes stay in one place.
     */
    public static int color(Context ctx, int res) {
        if ((res >>> 24) != 0) {
            // High byte set: this is an ARGB literal, not an id from aapt (ids live in 0x7f…).
            return res;
        }
        try {
            return ctx.getResources().getColor(res, ctx.getTheme());
        } catch (Throwable t) {
            // Last-resort net: a bad colour reference must never take a whole screen down at
            // onCreate time. Record it so it still shows up in DIAG instead of only in a crash.
            DiagnosticLog.recordThrottledProblem("uikit-color",
                    "Unknown colour resource 0x" + Integer.toHexString(res)
                            + " fell back to the default text colour", t);
            return FALLBACK_COLOR;
        }
    }

    /** Readable on the app's dark surfaces; used only when a colour cannot be resolved. */
    private static final int FALLBACK_COLOR = Color.rgb(0x9A, 0xAA, 0xB9);

    public static TextView text(Context ctx, String value, float sp, int colorRes) {
        TextView tv = new TextView(ctx);
        tv.setText(value);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color(ctx, colorRes));
        tv.setIncludeFontPadding(false);
        tv.setSingleLine(false);
        return tv;
    }

    public static LinearLayout card(Context ctx) {
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.bg_card);
        layout.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 10);
        layout.setLayoutParams(lp);
        return layout;
    }

    public static LinearLayout row(Context ctx) {
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static LinearLayout section(Context ctx, String title, int iconRes) {
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(dp(ctx, 4), dp(ctx, 16), dp(ctx, 4), dp(ctx, 4));

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(color(ctx, R.color.accent));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(ctx, 15), dp(ctx, 15));
        icon.setLayoutParams(iconLp);
        layout.addView(icon);

        TextView label = text(ctx, title, 11, R.color.muted);
        label.setAllCaps(true);
        label.setLetterSpacing(0.12f);
        label.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = dp(ctx, 8);
        layout.addView(label, labelLp);
        return layout;
    }

    public static View keyValue(Context ctx, String label, String value, int valueColorRes) {
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setPadding(0, dp(ctx, 6), 0, dp(ctx, 6));

        TextView key = text(ctx, label, 12, R.color.text_faint);
        LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4f);
        layout.addView(key, keyLp);

        TextView val = text(ctx, value, 12, valueColorRes);
        val.setGravity(Gravity.END);
        val.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 6f);
        layout.addView(val, valLp);
        return layout;
    }

    public static TextView chip(Context ctx, String label, boolean active) {
        TextView chip = text(ctx, label, 11, active ? R.color.accent : R.color.text_dim);
        chip.setAllCaps(true);
        chip.setLetterSpacing(0.08f);
        chip.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 20));
        bg.setColor(color(ctx, active ? R.color.surface : R.color.surface_alt));
        bg.setStroke(dp(ctx, 1), color(ctx, active ? R.color.accent : R.color.stroke));
        chip.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(ctx, 8);
        chip.setLayoutParams(lp);
        chip.setClickable(true);
        chip.setFocusable(true);
        return chip;
    }

    public static TextView button(Context ctx, String label, int styleRes) {
        TextView view = text(ctx, label, 11, R.color.text_dim);
        view.setAllCaps(true);
        view.setLetterSpacing(0.1f);
        view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(ctx, 16), dp(ctx, 11), dp(ctx, 16), dp(ctx, 11));
        view.setBackgroundResource(styleRes);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    public static View divider(Context ctx) {
        View view = new View(ctx);
        view.setBackgroundColor(color(ctx, R.color.stroke_soft));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        lp.topMargin = dp(ctx, 8);
        lp.bottomMargin = dp(ctx, 8);
        view.setLayoutParams(lp);
        return view;
    }

    public static LinearLayout emptyState(Context ctx, int iconRes, String title, String subtitle) {
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dp(ctx, 28), dp(ctx, 40), dp(ctx, 28), dp(ctx, 40));

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(color(ctx, R.color.muted_2));
        layout.addView(icon, new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)));

        TextView head = text(ctx, title, 15, R.color.text_dim);
        head.setGravity(Gravity.CENTER);
        head.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headLp.topMargin = dp(ctx, 14);
        layout.addView(head, headLp);

        TextView body = text(ctx, subtitle, 12, R.color.text_faint);
        body.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(ctx, 6);
        layout.addView(body, bodyLp);
        return layout;
    }

    public static GradientDrawable rounded(int fill, int stroke, int radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(radiusPx);
        drawable.setColor(fill);
        if (stroke != 0) {
            drawable.setStroke(1, stroke);
        }
        return drawable;
    }

    public static GradientDrawable pill(int fill, int radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(radiusPx);
        drawable.setColor(fill);
        return drawable;
    }

    public static void tint(TextView view, int colorRes) {
        view.setTextColor(color(view.getContext(), colorRes));
    }

    public static void applyRipple(View view) {
        try {
            view.setForegroundTintList(ColorStateList.valueOf(color(view.getContext(), R.color.accent_dim)));
        } catch (Throwable ignored) {
            // pre-23 devices
        }
    }
}
