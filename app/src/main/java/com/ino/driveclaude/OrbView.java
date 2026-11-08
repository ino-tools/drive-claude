package com.ino.driveclaude;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** 声で動く、透明感のある球体。状態（待機・聞いてる・考え中・話してる）で色と動きが変わる。 */
final class OrbView extends View {
    private String state = "stopped";
    private float t;                 // 0..1 を繰り返す
    private float level = 0.12f;     // 波の大きさ（なめらかに変化）
    private ValueAnimator anim;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path clip = new Path();

    // 状態ごとの色 {明るい芯, 中間, 縁, ハロ}
    private static final int[] IDLE = {0xFFC4F8FF, 0xFF37C8FF, 0xFF5B3CFF, 0xFF35D4FF};
    private static final int[] LISTEN = {0xFFD2FFF0, 0xFF2EF2C0, 0xFF1D7BFF, 0xFF2EF2C0};
    private static final int[] THINK = {0xFFEBD8FF, 0xFFB48CFF, 0xFF5A2BD6, 0xFFB48CFF};
    private static final int[] SPEAK = {0xFFD6F0FF, 0xFF3CB4FF, 0xFF6A3CFF, 0xFF4CC2FF};

    OrbView(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    void setState(String s) { state = s; }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(2600);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    private int[] colors() {
        switch (state) {
            case "listening": return LISTEN;
            case "thinking": return THINK;
            case "speaking": return SPEAK;
            default: return IDLE;
        }
    }

    private static int a(int color, float alpha) {
        return Color.argb(Math.round(255 * Math.max(0, Math.min(1, alpha))), Color.red(color), Color.green(color), Color.blue(color));
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2, cy = h / 2;
        float R = Math.min(w, h) * 0.29f;
        int[] c = colors();
        float two = (float) (2 * Math.PI);

        // 目標の波の大きさへなめらかに近づける
        float target = "speaking".equals(state) ? 0.62f + 0.18f * (float) Math.sin(t * two * 3)
                : "listening".equals(state) ? 0.34f + 0.12f * (float) Math.sin(t * two * 2)
                : "thinking".equals(state) ? 0.22f : 0.10f;
        level += (target - level) * 0.12f;

        float breathe = 1f + 0.025f * (float) Math.sin(t * two);

        // ハロ
        p.setShader(new RadialGradient(cx, cy, R * 1.9f, new int[]{a(c[3], 0.50f), a(c[3], 0.0f)}, new float[]{0.35f, 1f}, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        cv.drawCircle(cx, cy, R * 1.9f, p);
        p.setShader(null);

        // 広がる波紋（聞いてる／話してる）
        if ("listening".equals(state) || "speaking".equals(state)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(R * 0.025f);
            for (int i = 0; i < 3; i++) {
                float ph = (t + i / 3f) % 1f;
                float rr = R * (1.08f + 0.75f * ph);
                p.setColor(a(c[1], 0.45f * (1 - ph)));
                cv.drawCircle(cx, cy, rr, p);
            }
        } else {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(R * 0.02f);
            p.setColor(a(c[1], 0.28f));
            cv.drawCircle(cx, cy, R * 1.4f, p);
            p.setColor(a(c[1], 0.12f));
            cv.drawCircle(cx, cy, R * 1.62f, p);
        }

        // 考え中：球のまわりを回る光
        if ("thinking".equals(state)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(R * 0.07f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(a(c[0], 0.9f));
            RectF rf = new RectF(cx - R * 1.22f, cy - R * 1.22f, cx + R * 1.22f, cy + R * 1.22f);
            cv.drawArc(rf, t * 360f * 2, 70, false, p);
            p.setColor(a(c[1], 0.5f));
            cv.drawArc(rf, t * 360f * 2 + 180, 40, false, p);
            p.setStrokeCap(Paint.Cap.BUTT);
        }

        float r = R * breathe;

        // 球体（ガラス）
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.7f,
                new int[]{a(0xFFFFFFFF, 0.92f), a(c[0], 0.6f), a(c[1], 0.38f), a(c[2], 0.72f), a(c[2], 0.88f)},
                new float[]{0f, 0.2f, 0.52f, 0.85f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r, p);
        p.setShader(null);

        // 中の芯と声の波形
        cv.save();
        clip.reset();
        clip.addCircle(cx, cy, r, Path.Direction.CW);
        cv.clipPath(clip);
        p.setShader(new RadialGradient(cx + r * 0.15f, cy + r * 0.22f, r * 0.62f,
                new int[]{a(0xFFFFFFFF, 0.95f), a(c[0], 0.6f), a(c[0], 0f)}, new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r, p);
        p.setShader(null);

        path.reset();
        int n = 40;
        float x0 = cx - r * 0.85f, x1 = cx + r * 0.85f;
        for (int i = 0; i <= n; i++) {
            float u = i / (float) n;
            float env = (float) Math.sin(Math.PI * u);                 // 両端で0に絞る
            float wave = (float) (Math.sin(two * (u * 1.5f - t * ("stopped".equals(state) ? 0.5f : 2f)))
                    + 0.5 * Math.sin(two * (u * 2.5f + t * 3f)));
            float y = cy - wave * env * r * 0.55f * level / 0.62f * 0.62f;
            float x = x0 + (x1 - x0) * u;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(r * 0.17f);
        p.setColor(a(c[0], 0.45f));
        cv.drawPath(path, p);
        p.setStrokeWidth(r * 0.085f);
        p.setColor(0xF2FFFFFF);
        cv.drawPath(path, p);
        cv.restore();

        // 縁の反射光
        p.setStrokeWidth(r * 0.06f);
        p.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                new int[]{a(c[0], 0f), a(c[0], 0f), a(0xFFFFFFFF, 0.95f)}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r * 0.97f, p);
        p.setShader(null);

        // ハイライト
        p.setStyle(Paint.Style.FILL);
        p.setColor(a(0xFFFFFFFF, 0.5f));
        cv.save();
        cv.rotate(-35, cx - r * 0.38f, cy - r * 0.42f);
        cv.drawOval(new RectF(cx - r * 0.38f - r * 0.37f, cy - r * 0.42f - r * 0.19f, cx - r * 0.38f + r * 0.37f, cy - r * 0.42f + r * 0.19f), p);
        cv.restore();
    }
}
