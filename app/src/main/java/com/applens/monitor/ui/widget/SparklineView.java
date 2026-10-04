package com.applens.monitor.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import com.applens.monitor.core.Fmt;
import com.applens.monitor.ui.UiKit;

/**
 * Compact live chart used for throughput and event-rate graphs. Renders the last
 * {@code capacity} samples as a filled area with a bright leading edge.
 */
public class SparklineView extends View {

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float[] samples = new float[60];
    private int count;
    private int head;
    private int seriesColor = Color.rgb(0x22, 0xD3, 0xEE);
    private boolean filled = true;
    private String label = "";
    private String value = "";

    public SparklineView(Context context) {
        super(context);
        init(context);
    }

    public SparklineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SparklineView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(UiKit.dp(context, 1.8f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(seriesColor);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(UiKit.dp(context, 0.6f));
        gridPaint.setColor(UiKit.color(context, com.applens.monitor.R.color.stroke_soft));
        textPaint.setColor(UiKit.color(context, com.applens.monitor.R.color.text_faint));
        textPaint.setTextSize(UiKit.dp(context, 9));
        setWillNotDraw(false);
    }

    public void setColor(int color) {
        this.seriesColor = color;
        linePaint.setColor(color);
        invalidate();
    }

    public void setFilled(boolean filled) {
        this.filled = filled;
    }

    public void setHeader(String label, String value) {
        this.label = label == null ? "" : label;
        this.value = value == null ? "" : value;
        invalidate();
    }

    public void addSample(float value) {
        samples[head] = value;
        head = (head + 1) % samples.length;
        if (count < samples.length) {
            count++;
        }
        invalidate();
    }

    public void reset() {
        count = 0;
        head = 0;
        java.util.Arrays.fill(samples, 0f);
        invalidate();
    }

    public float max() {
        float max = 0;
        for (int i = 0; i < count; i++) {
            max = Math.max(max, samples[i]);
        }
        return max;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float headerSpace = UiKit.dp(getContext(), 14);
        float top = headerSpace;
        float bottom = h - UiKit.dp(getContext(), 2);
        if (bottom <= top) {
            return;
        }

        // baseline grid
        canvas.drawLine(0, bottom, w, bottom, gridPaint);
        canvas.drawLine(0, (top + bottom) / 2, w, (top + bottom) / 2, gridPaint);

        if (label.length() > 0) {
            canvas.drawText(label, 0, UiKit.dp(getContext(), 9), textPaint);
            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(value, w, UiKit.dp(getContext(), 9), textPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);
        }

        if (count < 2) {
            return;
        }
        float max = max();
        if (max <= 0) {
            max = 1;
        }
        float step = (float) w / Math.max(1, samples.length - 1);
        Path path = new Path();
        Path fill = new Path();
        int start = (head - count + samples.length) % samples.length;
        for (int i = 0; i < count; i++) {
            float x = step * (samples.length - count + i);
            float v = samples[(start + i) % samples.length];
            float y = bottom - (v / max) * (bottom - top);
            if (i == 0) {
                path.moveTo(x, y);
                fill.moveTo(x, bottom);
                fill.lineTo(x, y);
            } else {
                path.lineTo(x, y);
                fill.lineTo(x, y);
            }
        }
        fill.lineTo(step * (samples.length - 1), bottom);
        fill.close();

        if (filled) {
            fillPaint.setShader(new LinearGradient(0, top, 0, bottom,
                    withAlpha(seriesColor, 0x55), withAlpha(seriesColor, 0x02),
                    Shader.TileMode.CLAMP));
            fillPaint.setStyle(Paint.Style.FILL);
            canvas.drawPath(fill, fillPaint);
        }
        canvas.drawPath(path, linePaint);

        // leading dot
        float lastX = step * (samples.length - 1);
        float lastV = samples[(head - 1 + samples.length) % samples.length];
        float lastY = bottom - (lastV / max) * (bottom - top);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(seriesColor);
        canvas.drawCircle(lastX - UiKit.dp(getContext(), 1.5f), lastY,
                UiKit.dp(getContext(), 2.2f), dot);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    public static String formatRate(long bytesPerSecond) {
        return Fmt.rate(bytesPerSecond);
    }
}
