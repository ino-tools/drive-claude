package com.ino.driveclaude;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.telephony.SmsManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** Claudeが声の指示で使う道具（設定・メモ・記憶・予定・連絡先・SMS）。 */
final class Tools {
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private Tools() { }

    // ---------- definitions ----------

    private static JSONObject tool(String name, String desc, JSONObject props, String... required) throws Exception {
        JSONObject schema = new JSONObject().put("type", "object").put("properties", props);
        if (required.length > 0) {
            JSONArray r = new JSONArray();
            for (String s : required) r.put(s);
            schema.put("required", r);
        }
        return new JSONObject().put("name", name).put("description", desc).put("input_schema", schema);
    }

    private static JSONObject p(String type, String desc) throws Exception {
        return new JSONObject().put("type", type).put("description", desc);
    }

    static JSONArray definitions() throws Exception {
        JSONArray a = new JSONArray();
        a.put(tool("change_settings", "アプリの設定を変える。指定した項目だけ変わる。",
                new JSONObject()
                        .put("model", new JSONObject().put("type", "string").put("enum", new JSONArray().put("haiku").put("sonnet").put("opus"))
                                .put("description", "haiku=速い, sonnet=賢い, opus=最高性能"))
                        .put("speech_rate", p("number", "読み上げ速度。0.6〜2.0。標準1.2。「速く」なら+0.2程度"))
                        .put("web_search", p("boolean", "Web検索を使うか"))));
        a.put(tool("add_memo", "メモ・やることを保存する。", new JSONObject().put("text", p("string", "メモの内容")), "text"));
        a.put(tool("list_memos", "保存したメモを番号付きで取得する。", new JSONObject()));
        a.put(tool("delete_memo", "メモを番号で削除する（済んだやること等）。", new JSONObject().put("number", p("integer", "list_memosの番号")), "number"));
        a.put(tool("remember", "ユーザーについて長く役立つ事実を記憶する（次回以降の会話でも使う）。",
                new JSONObject().put("fact", p("string", "記憶する事実。例: 妻の名前は花子")), "fact"));
        a.put(tool("forget", "記憶を番号で消す。番号はシステムプロンプトの「覚えていること」の番号。",
                new JSONObject().put("number", p("integer", "番号")), "number"));
        a.put(tool("get_recent_summaries", "過去の会話のまとめを新しい順に取得する。",
                new JSONObject().put("count", p("integer", "件数。既定3"))));
        a.put(tool("add_calendar_event", "Googleカレンダー（端末のカレンダー）に予定やリマインダーを登録する。ユーザーがはっきり登録を頼み、さらに内容の読み上げ確認に了承した後だけ呼ぶ。",
                new JSONObject()
                        .put("title", p("string", "予定の名前"))
                        .put("start", p("string", "開始日時 yyyy-MM-ddTHH:mm（日本時間）。終日なら yyyy-MM-dd"))
                        .put("duration_minutes", p("integer", "長さ（分）。既定60"))
                        .put("all_day", p("boolean", "終日の予定か"))
                        .put("reminder_minutes", p("integer", "何分前に通知するか。リマインダー用途なら0"))
                        .put("location", p("string", "場所（任意）"))
                        .put("user_confirmed", p("boolean", "内容を読み上げてユーザーが「はい」等と了承したらtrue")),
                "title", "start", "user_confirmed"));
        a.put(tool("list_calendar_events", "指定日からの予定を取得する。",
                new JSONObject()
                        .put("date", p("string", "yyyy-MM-dd"))
                        .put("days", p("integer", "何日分か。既定1")),
                "date"));
        a.put(tool("find_contact", "電話帳から連絡先を探して電話番号を得る。", new JSONObject().put("name", p("string", "名前の一部")), "name"));
        a.put(tool("send_sms", "SMSを送信する。必ず事前に宛先と本文を読み上げてユーザーの了承を得てから呼ぶこと。",
                new JSONObject()
                        .put("phone_number", p("string", "電話番号"))
                        .put("recipient_name", p("string", "宛先の名前"))
                        .put("message", p("string", "本文"))
                        .put("user_confirmed", p("boolean", "宛先と本文を読み上げてユーザーが了承したらtrue")),
                "phone_number", "message", "user_confirmed"));
        return a;
    }

    // ---------- execution ----------

    static String execute(Context c, Prefs prefs, String name, JSONObject in) {
        try {
            switch (name) {
                case "change_settings": return changeSettings(prefs, in);
                case "add_memo":
                    Store.addMemo(c, in.optString("text"));
                    return "保存しました";
                case "list_memos": {
                    String s = Store.numbered(c, Store.MEMOS);
                    return s.isEmpty() ? "メモはありません" : s;
                }
                case "delete_memo":
                    return Store.removeAt(c, Store.MEMOS, in.optInt("number") - 1) ? "削除しました" : "その番号のメモはありません";
                case "remember":
                    Store.addMemory(c, in.optString("fact"));
                    return "記憶しました";
                case "forget":
                    return Store.removeAt(c, Store.MEMORY, in.optInt("number") - 1) ? "消しました" : "その番号の記憶はありません";
                case "get_recent_summaries": return recentSummaries(c, in.optInt("count", 3), true);
                case "add_calendar_event":
                    if (!in.optBoolean("user_confirmed", false)) return "未登録です。先に内容を読み上げて、登録してよいか確認してください。";
                    return addEvent(c, in);
                case "list_calendar_events": return listEvents(c, in);
                case "find_contact": return findContact(c, in.optString("name"));
                case "send_sms":
                    if (!in.optBoolean("user_confirmed", false)) return "未送信です。先に宛先と本文を読み上げて、送ってよいか確認してください。";
                    return sendSms(c, in);
                default: return "未知のツール: " + name;
            }
        } catch (Exception e) {
            return "エラー: " + e.getClass().getSimpleName() + " " + e.getMessage();
        }
    }

    private static boolean granted(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private static String changeSettings(Prefs prefs, JSONObject in) {
        if (in.has("model")) {
            String m = in.optString("model");
            if (m.startsWith("sonnet")) prefs.setModel(Prefs.MODEL_IDS[1]);
            else if (m.startsWith("opus")) prefs.setModel(Prefs.MODEL_IDS[2]);
            else prefs.setModel(Prefs.MODEL_IDS[0]);
        }
        if (in.has("speech_rate")) prefs.setRate((float) in.optDouble("speech_rate", 1.2));
        if (in.has("web_search")) prefs.setWebSearch(in.optBoolean("web_search", true));
        return String.format(java.util.Locale.JAPAN, "変更しました。現在: モデル=%s, 読み上げ速度=%.1f, Web検索=%s（モデル変更は次の発言から）",
                prefs.modelShort(), prefs.rate(), prefs.webSearch() ? "オン" : "オフ");
    }

    static String recentSummaries(Context c, int count, boolean detail) {
        JSONArray a = Store.load(c, Store.SESSIONS);
        if (a.length() == 0) return detail ? "過去の会話のまとめはありません" : "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = a.length() - 1; i >= 0 && n < count; i--, n++) {
            JSONObject s = a.optJSONObject(i);
            if (s == null) continue;
            sb.append("[").append(s.optString("time")).append("] ").append(s.optString("title")).append(": ")
                    .append(s.optString("summary"));
            if (detail) {
                JSONArray todos = s.optJSONArray("todos");
                if (todos != null && todos.length() > 0) sb.append(" やること: ").append(join(todos));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String join(JSONArray a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            if (i > 0) sb.append("、");
            sb.append(a.optString(i));
        }
        return sb.toString();
    }

    // ---------- calendar ----------

    private static long findCalendar(ContentResolver cr) {
        String[] proj = {CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.ACCOUNT_TYPE};
        long best = -1;
        int bestScore = -1;
        try (Cursor cur = cr.query(CalendarContract.Calendars.CONTENT_URI, proj,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=" + CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                null, null)) {
            if (cur == null) return -1;
            while (cur.moveToNext()) {
                int score = 0;
                if (cur.getInt(1) == 1) score += 2;
                if ("com.google".equals(cur.getString(2))) score += 1;
                if (score > bestScore) { bestScore = score; best = cur.getLong(0); }
            }
        }
        return best;
    }

    private static String addEvent(Context c, JSONObject in) throws Exception {
        if (!granted(c, Manifest.permission.WRITE_CALENDAR) || !granted(c, Manifest.permission.READ_CALENDAR)) {
            return "カレンダーの権限がありません。アプリの設定でカレンダーを許可してください。";
        }
        ContentResolver cr = c.getContentResolver();
        long calId = findCalendar(cr);
        if (calId < 0) return "書き込めるカレンダーが見つかりません";

        String start = in.optString("start");
        boolean allDay = in.optBoolean("all_day", false) || start.length() <= 10;
        long startMs, endMs;
        ContentValues v = new ContentValues();
        if (allDay) {
            LocalDate d = LocalDate.parse(start.substring(0, 10));
            startMs = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            endMs = d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            v.put(CalendarContract.Events.ALL_DAY, 1);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        } else {
            LocalDateTime dt = LocalDateTime.parse(start.length() == 16 ? start : start.substring(0, 16));
            startMs = dt.atZone(JST).toInstant().toEpochMilli();
            endMs = startMs + Math.max(1, in.optInt("duration_minutes", 60)) * 60_000L;
            v.put(CalendarContract.Events.EVENT_TIMEZONE, "Asia/Tokyo");
        }
        v.put(CalendarContract.Events.CALENDAR_ID, calId);
        v.put(CalendarContract.Events.TITLE, in.optString("title"));
        v.put(CalendarContract.Events.DTSTART, startMs);
        v.put(CalendarContract.Events.DTEND, endMs);
        if (in.has("location")) v.put(CalendarContract.Events.EVENT_LOCATION, in.optString("location"));
        Uri u = cr.insert(CalendarContract.Events.CONTENT_URI, v);
        if (u == null) return "登録に失敗しました";
        long eventId = ContentUris.parseId(u);
        if (in.has("reminder_minutes")) {
            ContentValues r = new ContentValues();
            r.put(CalendarContract.Reminders.EVENT_ID, eventId);
            r.put(CalendarContract.Reminders.MINUTES, Math.max(0, in.optInt("reminder_minutes")));
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
            cr.insert(CalendarContract.Reminders.CONTENT_URI, r);
        }
        return "登録しました: " + in.optString("title") + " " + start;
    }

    private static String listEvents(Context c, JSONObject in) throws Exception {
        if (!granted(c, Manifest.permission.READ_CALENDAR)) {
            return "カレンダーの権限がありません。アプリの設定でカレンダーを許可してください。";
        }
        LocalDate d = LocalDate.parse(in.optString("date"));
        int days = Math.max(1, Math.min(14, in.optInt("days", 1)));
        long begin = d.atStartOfDay(JST).toInstant().toEpochMilli();
        long end = d.plusDays(days).atStartOfDay(JST).toInstant().toEpochMilli();
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, begin);
        ContentUris.appendId(b, end);
        String[] proj = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY};
        DateTimeFormatter f = DateTimeFormatter.ofPattern("M/d H:mm");
        StringBuilder sb = new StringBuilder();
        try (Cursor cur = c.getContentResolver().query(b.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")) {
            if (cur != null) {
                while (cur.moveToNext()) {
                    Instant t = Instant.ofEpochMilli(cur.getLong(1));
                    String when = cur.getInt(2) == 1
                            ? t.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("M/d")) + " 終日"
                            : t.atZone(JST).format(f);
                    sb.append(when).append(" ").append(cur.getString(0)).append('\n');
                }
            }
        }
        return sb.length() == 0 ? "予定はありません" : sb.toString();
    }

    // ---------- contacts / sms ----------

    private static String findContact(Context c, String name) {
        if (!granted(c, Manifest.permission.READ_CONTACTS)) {
            return "連絡先の権限がありません。アプリの設定で連絡先を許可してください。";
        }
        Set<String> found = new LinkedHashSet<>();
        String[] proj = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER};
        Uri filter = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name));
        try (Cursor cur = c.getContentResolver().query(filter, proj, null, null, null)) {
            if (cur != null) while (cur.moveToNext() && found.size() < 6) found.add(cur.getString(0) + ": " + cur.getString(1));
        } catch (Exception ignored) { }
        if (found.isEmpty()) {
            try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", new String[]{"%" + name + "%"}, null)) {
                if (cur != null) while (cur.moveToNext() && found.size() < 6) found.add(cur.getString(0) + ": " + cur.getString(1));
            } catch (Exception ignored) { }
        }
        if (found.isEmpty()) return "「" + name + "」は見つかりませんでした";
        return String.join("\n", new ArrayList<>(found));
    }

    private static String sendSms(Context c, JSONObject in) {
        if (!granted(c, Manifest.permission.SEND_SMS)) {
            return "SMSの権限がありません。アプリの設定でSMSを許可してください。";
        }
        String number = in.optString("phone_number").replaceAll("[^0-9+]", "");
        String msg = in.optString("message");
        if (number.isEmpty() || msg.isEmpty()) return "電話番号か本文が空です";
        SmsManager sm = c.getSystemService(SmsManager.class);
        ArrayList<String> parts = sm.divideMessage(msg);
        sm.sendMultipartTextMessage(number, null, parts, null, null);
        return "送信しました（" + in.optString("recipient_name", number) + "）";
    }
}
