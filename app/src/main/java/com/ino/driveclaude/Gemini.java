package com.ino.driveclaude;

import android.content.Context;

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

/**
 * Gemini API クライアント。Claude.java と同じ道具（Tools）を使い、会話・Web検索・まとめを行う。
 * GeminiはWeb検索（Google検索）と関数呼び出しを同じリクエストに混ぜられないため、
 * Web検索は「web_search」という関数にして、中で別リクエスト（検索専用）を投げる。
 */
final class Gemini {
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static volatile boolean thinkingUnsupported;

    private Gemini() { }

    // ---------- conversation ----------

    /** 会話の1ターン。必要ならツールを実行し、最終的な返事のテキストを返す。 */
    static String ask(Context ctx, Prefs p, JSONArray history) throws Exception {
        JSONArray contents = new JSONArray();
        for (int i = 0; i < history.length(); i++) {
            JSONObject m = history.getJSONObject(i);
            String role = "assistant".equals(m.optString("role")) ? "model" : "user";
            contents.put(new JSONObject().put("role", role).put("parts", parts(m.optString("content"))));
        }
        JSONArray decls = declarations(p.webSearch());
        JSONObject system = new JSONObject().put("parts", parts(Claude.systemPrompt(ctx, p)));

        for (int iter = 0; iter < 8; iter++) {
            JSONObject body = new JSONObject()
                    .put("systemInstruction", system)
                    .put("contents", contents)
                    .put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", decls)))
                    .put("generationConfig", gen(2048, false));
            JSONObject resp = post(p, p.modelOf("gemini"), body);

            JSONArray cands = resp.optJSONArray("candidates");
            JSONObject cand = cands == null ? null : cands.optJSONObject(0);
            if (cand == null) return "すみません、その内容にはお答えできません。";
            JSONObject content = cand.optJSONObject("content");
            JSONArray ps = content == null ? null : content.optJSONArray("parts");
            if (ps == null) ps = new JSONArray();

            JSONArray results = new JSONArray();
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < ps.length(); i++) {
                JSONObject part = ps.getJSONObject(i);
                JSONObject fc = part.optJSONObject("functionCall");
                if (fc != null) {
                    String name = fc.optString("name");
                    JSONObject args = fc.optJSONObject("args");
                    if (args == null) args = new JSONObject();
                    String out = "web_search".equals(name)
                            ? search(p, args.optString("query"))
                            : Tools.execute(ctx, p, name, args);
                    results.put(new JSONObject().put("functionResponse", new JSONObject()
                            .put("name", name)
                            .put("response", new JSONObject().put("result", out))));
                } else if (part.has("text") && !part.optBoolean("thought", false)) {
                    text.append(part.optString("text"));
                }
            }
            if (results.length() > 0) {
                // Gemini 3 は関数呼び出しの「思考の署名」を次のリクエストで返す必要があるので、元の content をそのまま戻す
                contents.put(content);
                contents.put(new JSONObject().put("role", "user").put("parts", results));
                continue;
            }
            return text.toString().trim();
        }
        return "処理が長くなったので、いったん止めました。";
    }

    /** 会話全体を要約して {title, summary, decisions[], todos[], facts[]} を返す。 */
    static JSONObject summarize(Context ctx, Prefs p, String transcript) throws Exception {
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("parts", parts(Claude.summaryPrompt(ctx, transcript)))))
                .put("generationConfig", gen(3000, true));
        return Claude.parseSummary(text(post(p, p.modelOf("gemini"), body)));
    }

    // ---------- tools ----------

    /** Tools の定義（Anthropic形式）を Gemini の functionDeclarations に変換し、Web検索を足す。 */
    private static JSONArray declarations(boolean web) throws Exception {
        JSONArray src = Tools.definitions();
        JSONArray out = new JSONArray();
        for (int i = 0; i < src.length(); i++) {
            JSONObject t = src.getJSONObject(i);
            JSONObject d = new JSONObject().put("name", t.getString("name")).put("description", t.getString("description"));
            JSONObject schema = t.optJSONObject("input_schema");
            JSONObject props = schema == null ? null : schema.optJSONObject("properties");
            // Gemini は「項目が空のobject」を受け付けないので、項目なしの道具は parameters を省く
            if (props != null && props.length() > 0) d.put("parameters", schema);
            out.put(d);
        }
        if (web) {
            JSONObject q = new JSONObject().put("type", "string").put("description", "調べたい内容。日本語で具体的に（例: 名古屋 明日 天気）");
            out.put(new JSONObject()
                    .put("name", "web_search")
                    .put("description", "天気・ニュース・お店・時刻表など、最新の情報をWebで調べて要点を返す。")
                    .put("parameters", new JSONObject().put("type", "object")
                            .put("properties", new JSONObject().put("query", q))
                            .put("required", new JSONArray().put("query"))));
        }
        return out;
    }

    /** Google検索を使った調べもの（検索専用のリクエスト）。 */
    private static String search(Prefs p, String query) {
        if (p.searchCount() >= Prefs.SEARCH_STOP_AT) {
            return "今月のWeb検索は、無料枠（月" + Prefs.SEARCH_FREE_PER_MONTH + "回）に近づいたため止めています。検索なしで答えてください。";
        }
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm (E)", Locale.JAPAN);
            f.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
            String q = query + "\n（現在日時: " + f.format(new Date()) + "（日本時間）"
                    + (p.city().isEmpty() ? "" : "、ユーザーの現在地: " + p.city()) + "）";
            JSONObject body = new JSONObject()
                    .put("systemInstruction", new JSONObject().put("parts", parts("日本語で、事実だけを5文以内で簡潔に答える。URLや記号は使わない。")))
                    .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts(q))))
                    .put("tools", new JSONArray().put(new JSONObject().put("google_search", new JSONObject())))
                    .put("generationConfig", gen(1500, false));
            JSONObject resp = post(p, p.modelOf("gemini"), body);
            p.addSearch(queryCount(resp)); // 無料枠の見張り用に、実際に行われた検索の数を数える
            String t = text(resp);
            return t.isEmpty() ? "検索結果が得られませんでした" : t;
        } catch (Claude.ApiException e) {
            return "Web検索に失敗しました（" + e.code + "）: " + e.getMessage()
                    + " ※GeminiのWeb検索は有料プランでないと使えないことがあります。";
        } catch (Exception e) {
            return "Web検索に失敗しました: " + e.getMessage();
        }
    }

    // ---------- HTTP ----------

    private static JSONArray parts(String text) throws Exception {
        return new JSONArray().put(new JSONObject().put("text", text));
    }

    private static JSONObject gen(int maxTokens, boolean json) throws Exception {
        JSONObject g = new JSONObject().put("maxOutputTokens", maxTokens);
        if (json) g.put("responseMimeType", "application/json");
        if (!thinkingUnsupported) g.put("thinkingConfig", new JSONObject().put("thinkingLevel", "low")); // 音声は速さ優先
        return g;
    }

    /** 1回の回答で実行されたGoogle検索の数（わからなければ1）。 */
    private static int queryCount(JSONObject resp) {
        JSONArray c = resp.optJSONArray("candidates");
        JSONObject cand = c == null ? null : c.optJSONObject(0);
        JSONObject gm = cand == null ? null : cand.optJSONObject("groundingMetadata");
        JSONArray q = gm == null ? null : gm.optJSONArray("webSearchQueries");
        return q == null ? 1 : Math.max(1, q.length());
    }

    private static String text(JSONObject resp) {
        JSONArray c = resp.optJSONArray("candidates");
        JSONObject cand = c == null ? null : c.optJSONObject(0);
        JSONObject content = cand == null ? null : cand.optJSONObject("content");
        JSONArray ps = content == null ? null : content.optJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        if (ps != null) {
            for (int i = 0; i < ps.length(); i++) {
                JSONObject part = ps.optJSONObject(i);
                if (part != null && part.has("text") && !part.optBoolean("thought", false)) sb.append(part.optString("text"));
            }
        }
        return sb.toString().trim();
    }

    private static JSONObject post(Prefs p, String model, JSONObject body) throws Exception {
        try {
            return rawPost(p, model, body);
        } catch (Claude.ApiException e) {
            JSONObject g = body.optJSONObject("generationConfig");
            if (e.code == 400 && g != null && g.has("thinkingConfig")
                    && e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("thinking")) {
                thinkingUnsupported = true; // このモデルは思考レベルの指定に未対応。外して再送する
                g.remove("thinkingConfig");
                return rawPost(p, model, body);
            }
            throw e;
        }
    }

    private static JSONObject rawPost(Prefs p, String model, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + model + ":generateContent").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(90000);
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("x-goog-api-key", p.keyOf("gemini"));
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                String msg = "HTTP " + code;
                try { msg = new JSONObject(resp).getJSONObject("error").getString("message"); } catch (Exception ignored) { }
                // キーが違うときGeminiは400で返す。アプリ側では401（キーエラー）として扱う
                if (code == 400 && msg.contains("API key not valid")) code = 401;
                throw new Claude.ApiException(code, msg);
            }
            JSONObject r = new JSONObject(resp);
            Budget.recordGemini(p, model, r);
            return r;
        } finally {
            c.disconnect();
        }
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
