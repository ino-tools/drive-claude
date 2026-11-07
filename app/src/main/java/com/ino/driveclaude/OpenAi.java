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

/** OpenAI互換（Chat Completions）クライアント。ChatGPT(OpenAI)とGrok(xAI)で共通。Claude.java と同じ道具（Tools）を使う。 */
final class OpenAi {
    private OpenAi() { }

    static String url(String engine) {
        return "grok".equals(engine) ? "https://api.x.ai/v1/chat/completions" : "https://api.openai.com/v1/chat/completions";
    }

    static String ask(Context ctx, Prefs p, JSONArray history) throws Exception {
        String e = p.engine();
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", Claude.systemPrompt(ctx, p)));
        for (int i = 0; i < history.length(); i++) {
            JSONObject m = history.getJSONObject(i);
            msgs.put(new JSONObject().put("role", m.optString("role")).put("content", m.optString("content")));
        }
        JSONArray tools = tools();
        for (int iter = 0; iter < 8; iter++) {
            JSONObject body = new JSONObject()
                    .put("model", p.modelOf(e))
                    .put("messages", msgs)
                    .put("tools", tools)
                    .put("max_completion_tokens", 2048);
            JSONObject resp = post(p, e, body);
            JSONArray choices = resp.optJSONArray("choices");
            JSONObject msg = choices == null || choices.length() == 0 ? null : choices.getJSONObject(0).optJSONObject("message");
            if (msg == null) return "すみません、その内容にはお答えできません。";
            JSONArray calls = msg.optJSONArray("tool_calls");
            if (calls != null && calls.length() > 0) {
                msgs.put(msg);
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject c = calls.getJSONObject(i);
                    JSONObject fn = c.optJSONObject("function");
                    String name = fn == null ? "" : fn.optString("name");
                    JSONObject args;
                    try { args = new JSONObject(fn == null ? "{}" : fn.optString("arguments", "{}")); }
                    catch (Exception ex) { args = new JSONObject(); }
                    String out = Tools.execute(ctx, p, name, args);
                    msgs.put(new JSONObject().put("role", "tool").put("tool_call_id", c.optString("id")).put("content", out));
                }
                continue;
            }
            return msg.isNull("content") ? "" : msg.optString("content").trim();
        }
        return "処理が長くなったので、いったん止めました。";
    }

    static JSONObject summarize(Context ctx, Prefs p, String transcript) throws Exception {
        String e = p.engine();
        JSONObject body = new JSONObject()
                .put("model", p.modelOf(e))
                .put("max_completion_tokens", 3000)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", Claude.summaryPrompt(ctx, transcript))));
        JSONObject resp = post(p, e, body);
        JSONArray choices = resp.optJSONArray("choices");
        JSONObject msg = choices == null || choices.length() == 0 ? null : choices.getJSONObject(0).optJSONObject("message");
        return Claude.parseSummary(msg == null ? "" : msg.optString("content"));
    }

    /** Tools の定義（Anthropic形式）を OpenAI の tools 形式に変換。 */
    private static JSONArray tools() throws Exception {
        JSONArray src = Tools.definitions();
        JSONArray out = new JSONArray();
        for (int i = 0; i < src.length(); i++) {
            JSONObject t = src.getJSONObject(i);
            JSONObject fn = new JSONObject().put("name", t.getString("name")).put("description", t.getString("description"))
                    .put("parameters", t.getJSONObject("input_schema"));
            out.put(new JSONObject().put("type", "function").put("function", fn));
        }
        return out;
    }

    private static JSONObject post(Prefs p, String e, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url(e)).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(90000);
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("authorization", "Bearer " + p.keyOf(e));
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                String msg = "HTTP " + code;
                try { msg = new JSONObject(resp).getJSONObject("error").getString("message"); } catch (Exception ignored) { }
                throw new Claude.ApiException(code, msg);
            }
            JSONObject r = new JSONObject(resp);
            Budget.recordOpenAi(p, e, body.optString("model"), r);
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
