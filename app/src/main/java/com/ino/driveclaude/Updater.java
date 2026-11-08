package com.ino.driveclaude;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** GitHub の最新リリース（build-N）を調べ、アプリ内でダウンロードして更新する。署名が同じなので上書きインストールになる。 */
final class Updater {
    static final String API = "https://api.github.com/repos/ino-tools/drive-claude/releases/latest";
    static final String ACTION = "com.ino.driveclaude.INSTALL_RESULT";

    static final class Info {
        int build;
        String url;
        long size;
    }

    interface Progress { void on(int percent); }

    private Updater() { }

    /** いま入っているビルド番号（versionCode）。 */
    static int current(Context c) {
        try {
            return (int) c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 新しい版があれば Info、なければ null。ネットワークを使うのでメインスレッドでは呼ばない。 */
    static Info check(Context c) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(API).openConnection();
        try {
            h.setConnectTimeout(10000);
            h.setReadTimeout(15000);
            h.setRequestProperty("accept", "application/vnd.github+json");
            if (h.getResponseCode() != 200) return null;
            String body = read(h.getInputStream());
            JSONObject rel = new JSONObject(body);
            String tag = rel.optString("tag_name");
            if (!tag.startsWith("build-")) return null;
            int build = Integer.parseInt(tag.substring(6).trim());
            if (build <= current(c)) return null;
            JSONArray assets = rel.optJSONArray("assets");
            if (assets == null) return null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if ("drive-claude.apk".equals(a.optString("name"))) {
                    Info info = new Info();
                    info.build = build;
                    info.url = a.optString("browser_download_url");
                    info.size = a.optLong("size", -1);
                    return info;
                }
            }
            return null;
        } finally {
            h.disconnect();
        }
    }

    /** ダウンロードしながらインストーラーに渡す。完了すると確認画面が出る。 */
    static void downloadAndInstall(Context c, Info info, Progress cb) throws Exception {
        HttpURLConnection h = null;
        int code = 0;
        for (int i = 0; i < 4; i++) {
            try {
                if (h != null) h.disconnect();
                h = (HttpURLConnection) new URL(info.url).openConnection();
                h.setConnectTimeout(15000);
                h.setReadTimeout(60000);
                code = h.getResponseCode();
                if (code == 200) break;
            } catch (java.io.IOException e) {
                code = -1;
            }
            if (code != -1 && code < 500 && code != 429) break;
            try { Thread.sleep(2000L * (i + 1)); } catch (InterruptedException ignored) { }
        }
        if (code != 200) {
            if (h != null) h.disconnect();
            throw new Exception("ダウンロードできませんでした（" + code + "）。少し待ってからもう一度タップしてください");
        }
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(sp);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            long total = info.size > 0 ? info.size : h.getContentLengthLong();
            try (InputStream in = h.getInputStream(); OutputStream os = s.openWrite("drive-claude.apk", 0, total > 0 ? total : -1)) {
                byte[] buf = new byte[16384];
                long done = 0;
                int n, last = -1;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    if (total > 0 && cb != null) {
                        int pct = (int) (done * 100 / total);
                        if (pct != last) { last = pct; cb.on(pct); }
                    }
                }
                s.fsync(os);
            }
            Intent it = new Intent(c, UpdateReceiver.class).setAction(ACTION).setPackage(c.getPackageName());
            PendingIntent pend = PendingIntent.getBroadcast(c, id, it, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pend.getIntentSender());
        } catch (Exception e) {
            s.abandon();
            throw e;
        } finally {
            s.close();
            h.disconnect();
        }
    }

    static boolean canInstall(Context c) {
        PackageManager pm = c.getPackageManager();
        return pm.canRequestPackageInstalls();
    }

    private static String read(InputStream is) throws Exception {
        try (InputStream in = is) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
