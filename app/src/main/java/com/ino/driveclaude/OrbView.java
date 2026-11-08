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
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 声で動く、透明感のある球体。状態（待機・聞いてる・考え中・話してる）で色と動きが変わる。
 * 動きはすべて「時間で積み上げる位相」と「なめらかに近づく値」だけで作っているので、
 * ループの継ぎ目や、状態が変わった瞬間の急な変化が出ない。
 */
final class OrbView extends View {
    private String state = "stopped";
    private String brand = "", model = "";
    private ValueAnimator ticker;
    private long lastNs;

    // 位相（周期=1。小数部だけを使うので、いつまで動かしても継ぎ目がない）
    private float pWave, pWave2, pBreathe, pRipple, pArc, pMod;
    // なめらかに近づく値
    private float amp = 0.10f, speed = 0.35f, rip = 0f, think = 0f;
    private final float[] col = {0xC4, 0xF8, 0xFF, 0x37, 0xC8, 0xFF, 0x5B, 0x3C, 0xFF, 0x35, 0xD4, 0xFF};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path clip = new Path();
    private final RectF rect = new RectF();

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

    /** 奥にうっすら映す、使っているAIの名前とモデル名。 */
    void setBrand(String name, String modelId) {
        brand = name == null ? "" : name;
        model = modelId == null ? "" : modelId;
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastNs = 0;
        ticker = ValueAnimator.ofFloat(0f, 1f);
        ticker.setDuration(1000);
        ticker.setRepeatCount(ValueAnimator.INFINITE);
        ticker.setInterpolator(new LinearInterpolator());
        ticker.addUpdateListener(a -> invalidate());
        ticker.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (ticker != null) ticker.cancel();
        super.onDetachedFromWindow();
    }

    private int[] target() {
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

    private int c(int i) {
        return Color.rgb(Math.round(col[i * 3]), Math.round(col[i * 3 + 1]), Math.round(col[i * 3 + 2]));
    }

    private static float frac(float v) { return v - (float) Math.floor(v); }

    private static float ease(float cur, float tgt, float dt, float k) {
        return cur + (tgt - cur) * (1f - (float) Math.exp(-dt * k));
    }

    private void step(float dt) {
        boolean speaking = "speaking".equals(state), listening = "listening".equals(state), thinking = "thinking".equals(state);
        float tAmp = speaking ? 0.60f + 0.16f * (float) Math.sin(2 * Math.PI * pMod)
                : listening ? 0.34f + 0.10f * (float) Math.sin(2 * Math.PI * pMod)
                : thinking ? 0.22f : 0.10f;
        float tSpeed = speaking ? 1.3f : listening ? 0.8f : thinking ? 0.5f : 0.35f;
        amp = ease(amp, tAmp, dt, 5f);
        speed = ease(speed, tSpeed, dt, 3f);
        rip = ease(rip, (speaking || listening) ? 1f : 0f, dt, 2.5f);
        think = ease(think, thinking ? 1f : 0f, dt, 3f);
        int[] tc = target();
        for (int i = 0; i < 4; i++) {
            col[i * 3] = ease(col[i * 3], Color.red(tc[i]), dt, 4f);
            col[i * 3 + 1] = ease(col[i * 3 + 1], Color.green(tc[i]), dt, 4f);
            col[i * 3 + 2] = ease(col[i * 3 + 2], Color.blue(tc[i]), dt, 4f);
        }
        pWave = frac(pWave + dt * speed);
        pWave2 = frac(pWave2 + dt * speed * 1.5f);
        pBreathe = frac(pBreathe + dt * 0.38f);
        pRipple = frac(pRipple + dt * 0.42f);
        pArc = frac(pArc + dt * 0.8f);
        pMod = frac(pMod + dt * 1.6f);
    }

    @Override protected void onDraw(Canvas cv) {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0f : Math.min(0.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        step(dt);

        float w = getWidth(), h = getHeight();
        float cx = w / 2, cy = h / 2;
        float R = Math.min(w, h) * 0.29f;
        float two = (float) (2 * Math.PI);
        int c0 = c(0), c1 = c(1), c2 = c(2), c3 = c(3);

        // ハロ（画面の端で切れないよう、半径は view の内側に収める）
        float halo = Math.min(w, h) * 0.49f;
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx, cy, halo, new int[]{a(c3, 0.50f), a(c3, 0f)}, new float[]{0.35f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, halo, p);
        p.setShader(null);

        // 奥にうっすら映す、AIの名前（球体が透けるので、ガラス越しに見える）
        if (!brand.isEmpty()) {
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            p.setLetterSpacing(0.12f);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(R * 0.5f);
            float tw = p.measureText(brand);
            float maxW = R * 1.7f;
            if (tw > maxW) p.setTextSize(R * 0.5f * maxW / tw);
            p.setColor(a(0xFFFFFFFF, 0.16f));
            cv.drawText(brand, cx, cy + R * 0.14f, p);
            if (!model.isEmpty()) {
                p.setTextSize(R * 0.16f);
                p.setLetterSpacing(0.06f);
                float mw = p.measureText(model);
                if (mw > maxW) p.setTextSize(R * 0.16f * maxW / mw);
                p.setColor(a(0xFFFFFFFF, 0.14f));
                cv.drawText(model, cx, cy + R * 0.42f, p);
            }
            p.setLetterSpacing(0f);
            p.setTypeface(Typeface.DEFAULT);
        }

        // 波紋：「聞いてる／話してる」のときだけ広がる。待機のリングとは、なめらかに入れ替わる
        p.setStyle(Paint.Style.STROKE);
        if (rip > 0.01f) {
            p.setStrokeWidth(R * 0.025f);
            for (int i = 0; i < 3; i++) {
                float ph = frac(pRipple + i / 3f);
                float fade = (float) Math.sin(Math.PI * Math.min(1f, ph * 1.0f)); // 生まれる時も消える時も透明
                p.setColor(a(c1, 0.45f * (1 - ph) * fade * rip));
                cv.drawCircle(cx, cy, R * (1.08f + 0.75f * ph), p);
            }
        }
        float idle = 1f - rip;
        p.setStrokeWidth(R * 0.02f);
        p.setColor(a(c1, 0.28f * idle));
        cv.drawCircle(cx, cy, R * 1.4f, p);
        p.setColor(a(c1, 0.12f * idle));
        cv.drawCircle(cx, cy, R * 1.62f, p);

        // 考え中：球のまわりを回る光（出入りはなめらかに）
        if (think > 0.01f) {
            p.setStrokeWidth(R * 0.07f);
            p.setStrokeCap(Paint.Cap.ROUND);
            rect.set(cx - R * 1.22f, cy - R * 1.22f, cx + R * 1.22f, cy + R * 1.22f);
            p.setColor(a(c0, 0.9f * think));
            cv.drawArc(rect, pArc * 360f, 70, false, p);
            p.setColor(a(c1, 0.5f * think));
            cv.drawArc(rect, pArc * 360f + 180, 40, false, p);
            p.setStrokeCap(Paint.Cap.BUTT);
        }

        float r = R * (1f + 0.025f * (float) Math.sin(two * pBreathe));

        // 球体（ガラス）
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.7f,
                new int[]{a(0xFFFFFFFF, 0.92f), a(c0, 0.6f), a(c1, 0.38f), a(c2, 0.72f), a(c2, 0.88f)},
                new float[]{0f, 0.2f, 0.52f, 0.85f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r, p);
        p.setShader(null);

        // 中の芯と声の波形
        cv.save();
        clip.reset();
        clip.addCircle(cx, cy, r, Path.Direction.CW);
        cv.clipPath(clip);
        p.setShader(new RadialGradient(cx + r * 0.15f, cy + r * 0.22f, r * 0.62f,
                new int[]{a(0xFFFFFFFF, 0.95f), a(c0, 0.6f), a(c0, 0f)}, new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r, p);
        p.setShader(null);

        path.reset();
        int n = 40;
        float x0 = cx - r * 0.85f, x1 = cx + r * 0.85f;
        for (int i = 0; i <= n; i++) {
            float u = i / (float) n;
            float env = (float) Math.sin(Math.PI * u);
            float wave = (float) (Math.sin(two * (u * 1.5f - pWave)) + 0.5 * Math.sin(two * (u * 2.5f + pWave2)));
            float y = cy - wave * env * r * 0.55f * amp;
            float x = x0 + (x1 - x0) * u;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(r * 0.17f);
        p.setColor(a(c0, 0.45f));
        cv.drawPath(path, p);
        p.setStrokeWidth(r * 0.085f);
        p.setColor(0xF2FFFFFF);
        cv.drawPath(path, p);
        cv.restore();

        // 縁の反射光
        p.setStrokeWidth(r * 0.06f);
        p.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                new int[]{a(c0, 0f), a(c0, 0f), a(0xFFFFFFFF, 0.95f)}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r * 0.97f, p);
        p.setShader(null);

        // ハイライト
        p.setStyle(Paint.Style.FILL);
        p.setColor(a(0xFFFFFFFF, 0.5f));
        cv.save();
        cv.rotate(-35, cx - r * 0.38f, cy - r * 0.42f);
        rect.set(cx - r * 0.75f, cy - r * 0.61f, cx - r * 0.01f, cy - r * 0.23f);
        cv.drawOval(rect, p);
        cv.restore();
    }
}
