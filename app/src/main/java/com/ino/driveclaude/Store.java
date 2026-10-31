package com.ino.driveclaude;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** メモ・記憶・会話まとめを端末内のJSONファイルに保存する。 */
final class Store {
    static final String MEMOS = "memos.json";
    static final String MEMORY = "memory.json";
    static final String SESSIONS = "sessions.json";

    private Store() { }

    static String now() {
        return new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(new Date());
    }

    static synchronized JSONArray load(Context c, String name) {
        File f = new File(c.getFilesDir(), name);
        if (!f.exists()) return new JSONArray();
        try {
            return new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static synchronized void save(Context c, String name, JSONArray a) {
        try {
            Files.write(new File(c.getFilesDir(), name).toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    static synchronized boolean removeAt(Context c, String name, int idx) {
        JSONArray a = load(c, name);
        if (idx < 0 || idx >= a.length()) return false;
        a.remove(idx);
        save(c, name, a);
        return true;
    }

    private static void addText(Context c, String name, String text, int cap) {
        JSONArray a = load(c, name);
        try {
            for (int i = 0; i < a.length(); i++) {
                if (text.equals(a.getJSONObject(i).optString("text"))) return; // 重複は保存しない
            }
            a.put(new JSONObject().put("text", text).put("time", now()));
        } catch (Exception ignored) { }
        while (a.length() > cap) a.remove(0);
        save(c, name, a);
    }

    static synchronized void addMemo(Context c, String text) { addText(c, MEMOS, text, 200); }

    static synchronized void addMemory(Context c, String text) { addText(c, MEMORY, text, 60); }

    static synchronized void addSession(Context c, JSONObject session) {
        JSONArray a = load(c, SESSIONS);
        a.put(session);
        while (a.length() > 50) a.remove(0);
        save(c, SESSIONS, a);
    }

    /** 番号付きの一覧テキスト（1始まり）。 */
    static String numbered(Context c, String name) {
        JSONArray a = load(c, name);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            sb.append(i + 1).append(". ").append(o.optString("text")).append('\n');
        }
        return sb.toString();
    }
}
