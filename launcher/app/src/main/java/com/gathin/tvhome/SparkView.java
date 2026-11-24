package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * The theme's sparkles: a handful of four-point stars drifting up through the profile's teal band.
 * Cheap on purpose (one paint, one path, no bitmaps), and hidden when reduced motion is on.
 */
public final class SparkView extends View {
    private static final int COUNT = 26;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path star = new Path();
    private final float[] x = new float[COUNT];
    private final float[] y = new float[COUNT];
    private final float[] radius = new float[COUNT];
    private final float[] speed = new float[COUNT];
    private final float[] phase = new float[COUNT];
    private boolean running;
    private long lastFrame;

    public SparkView(Context context) {
        super(context);
        init();
    }

    public SparkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        Random random = new Random(7);
        for (int i = 0; i < COUNT; i++) {
            x[i] = random.nextFloat();
            y[i] = random.nextFloat();
            radius[i] = 2f + random.nextFloat() * 4f;
            speed[i] = 0.02f + random.nextFloat() * 0.05f;
            phase[i] = random.nextFloat() * 6.28f;
        }
        paint.setColor(0xFFFFFFFF);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        lastFrame = 0;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0f : Math.min(0.05f, (now - lastFrame) / 1_000_000_000f);
        lastFrame = now;
        float w = getWidth();
        float h = getHeight();
        float seconds = now / 1_000_000_000f;
        for (int i = 0; i < COUNT; i++) {
            y[i] -= speed[i] * dt;
            if (y[i] < -0.1f) y[i] = 1.1f;
            float alpha = 0.2f + 0.6f * (0.5f + 0.5f * (float) Math.sin(seconds * 2.2f + phase[i]));
            paint.setAlpha((int) (255 * alpha));
            float cx = x[i] * w;
            float cy = y[i] * h;
            float r = radius[i];
            float inner = r * 0.3f;
            star.reset();
            star.moveTo(cx, cy - r);
            star.lineTo(cx + inner, cy - inner);
            star.lineTo(cx + r, cy);
            star.lineTo(cx + inner, cy + inner);
            star.lineTo(cx, cy + r);
            star.lineTo(cx - inner, cy + inner);
            star.lineTo(cx - r, cy);
            star.lineTo(cx - inner, cy - inner);
            star.close();
            canvas.drawPath(star, paint);
        }
        if (running) postInvalidateOnAnimation();
    }
}
