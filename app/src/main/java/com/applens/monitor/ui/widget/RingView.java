package com.applens.monitor.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.applens.monitor.ui.UiKit;

import java.util.ArrayList;
import java.util.List;

/** Donut chart used for the storage breakdown. */
public class RingView extends View {

    public static final class Slice {
        public final String label;
        public final long value;
        public final int color;

        public Slice(String label, long value, int color) {
            this.label = label;
            this.value = value;
            this.color = color;
        }
    }

    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final List<Slice> slices = new ArrayList<>();
    private String centre = "";
    private String sub = "";

    public RingView(Context context) {
        super(context);
        init(context);
    }

    public RingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public RingView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(UiKit.dp(context, 16));
        arcPaint.setStrokeCap(Paint.Cap.BUTT);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(UiKit.dp(context, 16));
        trackPaint.setColor(UiKit.color(context, com.applens.monitor.R.color.surface_alt));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        subPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setSlices(List<Slice> newSlices, String centre, String sub) {
        slices.clear();
        if (newSlices != null) {
            slices.addAll(newSlices);
        }
        this.centre = centre == null ? "" : centre;
        this.sub = sub == null ? "" : sub;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float size = Math.min(w, h);
        float pad = arcPaint.getStrokeWidth();
        float left = (w - size) / 2 + pad;
        float top = (h - size) / 2 + pad;
        oval.set(left, top, left + size - pad * 2, top + size - pad * 2);

        canvas.drawArc(oval, 0, 360, false, trackPaint);
        float total = 0;
        for (Slice s : slices) {
            total += Math.max(0, s.value);
        }
        if (total > 0) {
            float start = -90;
            for (Slice s : slices) {
                float sweep = 360f * (Math.max(0, s.value) / total);
                if (sweep <= 0.1f) {
                    continue;
                }
                arcPaint.setColor(s.color);
                canvas.drawArc(oval, start, Math.max(0.6f, sweep - 1.2f), false, arcPaint);
                start += sweep;
            }
        }
        float cx = w / 2f;
        float cy = h / 2f;
        textPaint.setTextSize(UiKit.dp(getContext(), 17));
        textPaint.setColor(UiKit.color(getContext(), com.applens.monitor.R.color.text));
        canvas.drawText(centre, cx, cy + UiKit.dp(getContext(), 3), textPaint);
        subPaint.setTextSize(UiKit.dp(getContext(), 10));
        subPaint.setColor(UiKit.color(getContext(), com.applens.monitor.R.color.text_faint));
        canvas.drawText(sub, cx, cy + UiKit.dp(getContext(), 18), subPaint);
    }
}
