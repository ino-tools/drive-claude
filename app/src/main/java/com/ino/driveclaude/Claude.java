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

/** Anthropic Messages API クライアント（ツール実行ループ付き）。 */
final class Claude {

    static final class ApiException extends Exception {
        final int code;
        ApiException(int code, String msg) { super(msg); this.code = code; }
    }

    private Claude() { }

    /** 会話の1ターン。必要ならツールを実行し、最終的な返事のテキストを返す。 */
    static String ask(Context ctx, Prefs p, JSONArray history) throws Exception {
        if (p.gemini()) return Gemini.ask(ctx, p, history);
        JSONArray msgs = new JSONArray(history.toString());
        JSONArray tools = Tools.definitions();
        if (p.webSearch()) {
            JSONObject loc = new JSONObject().put("type", "approximate").put("country", "JP").put("timezone", "Asia/Tokyo");
            if (!p.city().isEmpty()) loc.put("city", p.city());
            tools.put(new JSONObject().put("type", "web_search_20250305").put("name", "web_search")
                    .put("max_uses", 3).put("user_location", loc));
        }
        String system = systemPrompt(ctx, p);

        for (int iter = 0; iter < 8; iter++) {
            JSONObject body = new JSONObject()
                    .put("model", p.model())
                    .put("max_tokens", 1024)
                    .put("system", system)
                    .put("tools", tools)
                    .put("messages", msgs);
            JSONObject resp = post(p, body);
            JSONArray content = resp.optJSONArray("content");
            if (content == null) content = new JSONArray();
            String stop = resp.optString("stop_reason");

            if ("tool_use".equals(stop)) {
                msgs.put(new JSONObject().put("role", "assistant").put("content", content));
                JSONArray results = new JSONArray();
                for (int i = 0; i < content.length(); i++) {
                    JSONObject b = content.getJSONObject(i);
                    if (!"tool_use".equals(b.optString("type"))) continue;
                    JSONObject input = b.optJSONObject("input");
                    String out = Tools.execute(ctx, p, b.optString("name"), input == null ? new JSONObject() : input);
                    results.put(new JSONObject().put("type", "tool_result")
                            .put("tool_use_id", b.optString("id")).put("content", out));
                }
                msgs.put(new JSONObject().put("role", "user").put("content", results));
                continue;
            }
            if ("pause_turn".equals(stop)) {
                msgs.put(new JSONObject().put("role", "assistant").put("content", content));
                continue;
            }
            return texts(content);
        }
        return "処理が長くなったので、いったん止めました。";
    }

    /** 会話全体を要約して {title, summary, decisions[], todos[], facts[]} を返す。 */
    static JSONObject summarize(Context ctx, Prefs p, String transcript) throws Exception {
        if (p.gemini()) return Gemini.summarize(ctx, p, transcript);
        JSONObject body = new JSONObject()
                .put("model", Prefs.MODEL_IDS[0])
                .put("max_tokens", 1500)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", summaryPrompt(ctx, transcript))));
        return parseSummary(texts(post(p, body).optJSONArray("content")));
    }

    static String summaryPrompt(Context ctx, String transcript) {
        return "以下は音声会話の記録です。あとで読み返すためのまとめを作ってください。\n"
                + "JSONだけを出力してください（前後に文章やコードブロックを付けない）。形式:\n"
                + "{\"title\":\"20文字以内の題\",\"summary\":\"話した内容の要点を3〜5文で\","
                + "\"decisions\":[\"決めたこと\"],\"todos\":[\"やること・宿題\"],"
                + "\"facts\":[\"ユーザー本人について次回以降も役立つ事実（家族、好み、予定、仕事など）。一時的な話題は入れない\"]}\n"
                + "該当がない配列は空にしてください。\n\n既に覚えていること（factsに重複して入れない）:\n"
                + Store.numbered(ctx, Store.MEMORY) + "\n会話記録:\n" + transcript;
    }

    static JSONObject parseSummary(String t) throws Exception {
        int s = t.indexOf('{'), e = t.lastIndexOf('}');
        if (s >= 0 && e > s) {
            try { return new JSONObject(t.substring(s, e + 1)); } catch (Exception ignored) { }
        }
        return new JSONObject().put("title", "会話").put("summary", t);
    }

    static String systemPrompt(Context ctx, Prefs p) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm (E)", Locale.JAPAN);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
        StringBuilder s = new StringBuilder();
        s.append("あなたはユーザーと音声だけで会話するアシスタントです。返答はそのまま音声合成で読み上げられます。\n");
        s.append("話し方:\n");
        s.append("- 前置き、注意書き、安全運転などの呼びかけは一切しない。聞かれたことにすぐ答える。\n");
        s.append("- 日本語で、基本は1〜3文。結論から話す。詳しくと言われたら詳しく話す。\n");
        s.append("- 箇条書き・記号・Markdown・URL・絵文字は使わない。数字や単位は読み上げやすく書く。\n");
        s.append("- 最新情報が必要ならWeb検索を使う。出典名は言わなくてよい。\n");
        s.append("できる操作（ツール）:\n");
        s.append("- 設定変更、メモ、予定・リマインダー登録、予定確認、連絡先検索、SMS送信、記憶、過去の会話のまとめ参照。\n");
        s.append("- 設定変更・メモ・記憶・予定確認は、頼まれたら確認せずに実行し結果を一言で伝える。\n");
        s.append("- 予定の登録はユーザーがはっきり頼んだときだけ。勝手に登録しない。登録前に「明日10時に歯医者、で入れていいですか」と内容を読み上げ、了承されてから user_confirmed=true で add_calendar_event を呼ぶ。\n");
        s.append("- SMSも送る前に宛先と本文を読み上げ「送っていいですか」と聞き、了承されてから user_confirmed=true で send_sms を呼ぶ。\n");
        s.append("- 家族などを呼び名で言われたら、覚えていることから実名を探してから find_contact を使う。\n");
        s.append("- LINEやメールは送れないので、頼まれたらSMSなら送れると伝える。\n");
        s.append("- ユーザーが自分について長く役立つこと（家族の名前、好み、よく行く場所など）を話したり「覚えて」と言ったら remember で保存する。\n");
        s.append("現在日時: ").append(f.format(new Date())).append("（日本時間）。");
        s.append("ユーザーのおおよその現在地: ").append(p.city().isEmpty() ? "不明" : p.city()).append("。\n");
        s.append("現在の設定: AI=").append(p.engineLabel())
                .append(String.format(Locale.JAPAN, ", 読み上げ速度=%.1f", p.rate()))
                .append(", Web検索=").append(p.webSearch() ? "オン" : "オフ").append("\n");
        String mem = Store.numbered(ctx, Store.MEMORY);
        if (!mem.isEmpty()) s.append("\nユーザーについて覚えていること:\n").append(mem);
        String recent = Tools.recentSummaries(ctx, 3, false);
        if (!recent.isEmpty()) s.append("\n最近の会話のまとめ（話題に関係あるときだけ使う）:\n").append(recent);
        if (!p.extra().isEmpty()) s.append("\n追加の指示: ").append(p.extra()).append("\n");
        return s.toString();
    }

    private static String texts(JSONArray content) {
        StringBuilder sb = new StringBuilder();
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject b = content.optJSONObject(i);
                if (b != null && "text".equals(b.optString("type"))) sb.append(b.optString("text"));
            }
        }
        return sb.toString().trim();
    }

    private static JSONObject post(Prefs p, JSONObject body) throws Exception {
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
                try { msg = new JSONObject(resp).getJSONObject("error").getString("message"); } catch (Exception ignored) { }
                throw new ApiException(code, msg);
            }
            return new JSONObject(resp);
        } finally {
            c.disconnect();
        }
    }

    /** 読み上げ前に記号やURLを取り除く。 */
    static String forSpeech(String t) {
        return t.replaceAll("https?://\\S+", "")
                .replaceAll("\\[(.*?)\\]\\(.*?\\)", "$1")
                .replaceAll("[*#`_>|]", "")
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
