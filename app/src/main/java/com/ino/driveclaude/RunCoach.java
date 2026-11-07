package com.ino.driveclaude;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Locale;

/** ランニングモード。GPSで距離とペースを測り、設定した距離ごとに声で知らせる（心拍はHeartRateから）。 */
final class RunCoach implements LocationListener {
    interface Say { void say(String text); }

    static volatile RunCoach current;

    private final Context ctx;
    private final Prefs prefs;
    private final Say say;
    private final LocationManager lm;
    private Location last;
    private double dist;            // m
    private long startMs, lastMarkMs;
    private double nextMark;        // m
    private int prevSplit;          // 秒
    private int bpm;
    private long bpmMs;
    private boolean running;
    private final ArrayDeque<double[]> win = new ArrayDeque<>(); // {時刻ms, 距離m} 直近のペース用

    RunCoach(Context c, Prefs p, Say s) {
        ctx = c.getApplicationContext();
        prefs = p;
        say = s;
        lm = ctx.getSystemService(LocationManager.class);
    }

    boolean running() { return running; }

    @SuppressLint("MissingPermission")
    boolean start() {
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, this, Looper.getMainLooper());
        } catch (Exception e) {
            return false;
        }
        running = true;
        startMs = lastMarkMs = System.currentTimeMillis();
        dist = 0;
        last = null;
        prevSplit = 0;
        nextMark = interval();
        current = this;
        return true;
    }

    /** 終了。まとめの文章を返す。 */
    String stop() {
        if (!running) return "ランニングモードは動いていません。";
        running = false;
        try { lm.removeUpdates(this); } catch (Exception ignored) { }
        current = null;
        long sec = (System.currentTimeMillis() - startMs) / 1000;
        if (dist < 50) return "ランニングを終了しました。";
        return String.format(Locale.JAPAN, "ランニングを終了します。%s、%s、平均ペースは%s毎キロでした。おつかれさまでした。",
                km(dist), dur(sec), pace((int) (sec / (dist / 1000.0))));
    }

    void onHeartRate(int b) { bpm = b; bpmMs = System.currentTimeMillis(); }

    private double interval() { return Math.max(0.25, prefs.runKm()) * 1000; }

    @Override
    public void onLocationChanged(Location loc) {
        if (!running || loc.getAccuracy() > 30) return;
        long now = System.currentTimeMillis();
        if (last != null) {
            float d = last.distanceTo(loc);
            double dt = Math.max(1, (loc.getTime() - last.getTime()) / 1000.0);
            if (d < 4 || d / dt > 12) return; // 止まっているときのブレ／ありえない速さは無視
            dist += d;
        }
        last = loc;
        win.addLast(new double[]{now, dist});
        while (win.size() > 2 && now - win.peekFirst()[0] > 30000) win.removeFirst();

        while (dist >= nextMark) {
            int split = (int) ((now - lastMarkMs) / 1000); // この区間にかかった秒数
            int perKm = (int) (split / (interval() / 1000));
            lastMarkMs = now;
            say.say(announce(nextMark, perKm));
            prevSplit = perKm;
            nextMark += interval();
        }
    }

    private String announce(double mark, int perKm) {
        long sec = (System.currentTimeMillis() - startMs) / 1000;
        StringBuilder sb = new StringBuilder();
        sb.append(km(mark)).append("。ペースは").append(pace(perKm)).append("毎キロ。");
        if (prevSplit > 0) {
            int diff = perKm - prevSplit;
            if (Math.abs(diff) >= 10) sb.append("前より").append(Math.abs(diff)).append("秒").append(diff < 0 ? "速いです。" : "遅いです。");
        }
        sb.append("平均は").append(pace((int) (sec / (mark / 1000.0)))).append("。経過").append(dur(sec)).append("。");
        sb.append(hrText());
        return sb.toString();
    }

    private String hrText() {
        if (bpm <= 0 || System.currentTimeMillis() - bpmMs > 20000) return "";
        StringBuilder sb = new StringBuilder("心拍は").append(bpm).append("。");
        int max = prefs.hrMax();
        if (max > 0 && bpm > max) sb.append("心拍が高めです。少しペースを落としましょう。");
        else if (max > 0 && bpm < max * 0.7) sb.append("余裕がありそうです。");
        return sb.toString();
    }

    /** 「今のペースは？」への即答（AIを使わない）。 */
    String statusSpeech() {
        if (!running) return "ランニングモードは動いていません。";
        if (dist < 20) return "まだ距離が測れていません。GPSをつかまえるまで少し走ってください。";
        long sec = (System.currentTimeMillis() - startMs) / 1000;
        StringBuilder sb = new StringBuilder();
        sb.append(km(dist)).append("、").append(dur(sec)).append("。平均ペース").append(pace((int) (sec / (dist / 1000.0)))).append("。");
        int now = recentPace();
        if (now > 0) sb.append("直近は").append(pace(now)).append("。");
        sb.append(hrText());
        return sb.toString();
    }

    /** AIのシステムプロンプトに入れる状況。 */
    String promptInfo() {
        if (!running) return "";
        long sec = (System.currentTimeMillis() - startMs) / 1000;
        StringBuilder sb = new StringBuilder("ランニング中。距離=").append(km(dist)).append("、経過=").append(dur(sec));
        if (dist >= 50) sb.append("、平均ペース=").append(pace((int) (sec / (dist / 1000.0))));
        int now = recentPace();
        if (now > 0) sb.append("、直近ペース=").append(pace(now));
        if (bpm > 0 && System.currentTimeMillis() - bpmMs <= 20000) sb.append("、心拍=").append(bpm);
        if (prefs.hrMax() > 0) sb.append("、心拍の上限の目安=").append(prefs.hrMax());
        return sb.toString();
    }

    private int recentPace() {
        if (win.size() < 2) return 0;
        double[] a = win.peekFirst(), b = win.peekLast();
        double m = b[1] - a[1], s = (b[0] - a[0]) / 1000.0;
        return m < 15 || s < 8 ? 0 : (int) (s / (m / 1000.0));
    }

    private static String km(double m) {
        double k = m / 1000.0;
        return Math.abs(k - Math.round(k)) < 0.005 ? Math.round(k) + "キロ" : String.format(Locale.JAPAN, "%.2fキロ", k);
    }

    static String pace(int secPerKm) {
        return secPerKm / 60 + "分" + secPerKm % 60 + "秒";
    }

    private static String dur(long sec) {
        return sec >= 3600 ? String.format(Locale.JAPAN, "%d時間%d分%d秒", sec / 3600, sec % 3600 / 60, sec % 60)
                : String.format(Locale.JAPAN, "%d分%d秒", sec / 60, sec % 60);
    }
}
