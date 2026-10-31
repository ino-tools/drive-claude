package com.ino.driveclaude;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Anthropic Messages API への最小クライアント。 */
final class Claude {

    static final class ApiException extends Exception {
        final int code;
        ApiException(int code, String msg) { super(msg); this.code = code; }
    }

    static String ask(Prefs p, JSONArray messages) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", p.model());
        body.put("max_tokens", 800);
        body.put("system", systemPrompt(p));
        body.put("messages", messages);

        if (p.webSearch()) {
            JSONObject loc = new JSONObject();
            loc.put("type", "approximate");
            if (!p.city().isEmpty()) loc.put("city", p.city());
            loc.put("country", "JP");
            loc.put("timezone", "Asia/Tokyo");
            JSONObject tool = new JSONObject();
            tool.put("type", "web_search_20250305");
            tool.put("name", "web_search");
            tool.put("max_uses", 3);
            tool.put("user_location", loc);
            body.put("tools", new JSONArray().put(tool));
        }

        HttpURLConnection c = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(90000);
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("x-api-key", p.apiKey());
            c.setRequestProperty("anthropic-version", "2023-06-01");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                String msg = "HTTP " + code;
                try {
                    msg = new JSONObject(resp).getJSONObject("error").getString("message");
                } catch (Exception ignored) { }
                throw new ApiException(code, msg);
            }
            JSONArray content = new JSONObject(resp).optJSONArray("content");
            StringBuilder sb = new StringBuilder();
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject b = content.getJSONObject(i);
                    if ("text".equals(b.optString("type"))) sb.append(b.optString("text"));
                }
            }
            return sb.toString().trim();
        } finally {
            c.disconnect();
        }
    }

    private static String systemPrompt(Prefs p) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy年M月d日(E) H時m分", Locale.JAPAN);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
        StringBuilder s = new StringBuilder();
        s.append("あなたは車を運転中のユーザーと音声で会話するアシスタントです。\n");
        s.append("返答はそのまま音声合成で読み上げられます。以下を厳守してください。\n");
        s.append("- 日本語で、基本は1〜3文、長くても200文字程度。結論から話す。\n");
        s.append("- 箇条書き・記号・Markdown・URL・絵文字は使わない。数字や単位は読み上げやすく書く。\n");
        s.append("- 運転の安全を最優先。画面を見る・操作する必要がある提案はしない。\n");
        s.append("- 長い説明が必要なときは要点だけ話し、「続きを聞きますか」と尋ねる。\n");
        s.append("- 最新情報が必要ならWeb検索を使い、出典名は言わなくてよい。\n");
        s.append("現在日時: ").append(f.format(new Date())).append("。");
        s.append("ユーザーのおおよその現在地: ").append(p.city().isEmpty() ? "不明" : p.city()).append("。\n");
        if (!p.extra().isEmpty()) s.append("追加の指示: ").append(p.extra()).append("\n");
        return s.toString();
    }

    /** 読み上げ前に記号やURLを取り除く。 */
    static String forSpeech(String t) {
        return t.replaceAll("https?://\\S+", "")
                .replaceAll("[*#`_>|]", "")
                .replaceAll("\\[(.*?)\\]\\(.*?\\)", "$1")
                .trim();
    }

    private static String read(InputStream is) throws Exception {
        if (is == null) return "";
        try (InputStream in = is) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
