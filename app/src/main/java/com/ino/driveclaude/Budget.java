package com.ino.driveclaude;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** AIごとの「上限額」と「期限日」の見張り。超えたらそのAIを止める。使った額はトークン数からの見積もり（目安）。 */
final class Budget {
    static final int ERR_STOPPED = 900; // アプリ側の上限・期限で止めたときのエラーコード
    static final double YEN = 150.0;

    private Budget() { }

    static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
        return f.format(new Date());
    }

    static boolean validDate(String s) {
        return s.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    /** 止める理由（読み上げ用の文）。使えるなら null。 */
    static String blocked(Prefs p, String e) {
        String name = Prefs.ENGINE_SPOKEN[Prefs.engineIndex(e)];
        String exp = p.expiryOf(e);
        if (Prefs.validDate(exp) && today().compareTo(exp) > 0) {
            return name + "の期限（" + exp + "）が過ぎたので止めました。設定で期限を延ばすか、別のAIに切り替えてください。";
        }
        double b = p.budgetOf(e);
        if (b > 0 && p.spentOf(e) >= b) {
            return name + "は設定した上限額（" + money(b) + "）に達したので止めました。設定で上限を増やすか、別のAIに切り替えてください。";
        }
        return null;
    }

    static String money(double usd) {
        return String.format(Locale.JAPAN, "$%.2f（約%d円）", usd, Math.round(usd * YEN));
    }

    static void record(Prefs p, String e, String model, long in, long out, double extraUsd) {
        double[] pr = p.priceOf(e, model);
        double usd = in * pr[0] / 1e6 + out * pr[1] / 1e6 + extraUsd;
        if (usd > 0) p.addSpent(e, usd);
    }

    /** Anthropic の usage から記録。Web検索1回=$0.01。 */
    static void recordClaude(Prefs p, JSONObject resp) {
        JSONObject u = resp.optJSONObject("usage");
        if (u == null) return;
        JSONObject st = u.optJSONObject("server_tool_use");
        int searches = st == null ? 0 : st.optInt("web_search_requests", 0);
        record(p, "claude", resp.optString("model", p.modelOf("claude")),
                u.optLong("input_tokens") + u.optLong("cache_read_input_tokens") + u.optLong("cache_creation_input_tokens"),
                u.optLong("output_tokens"), searches * 0.01);
    }

    /** Gemini の usageMetadata から記録。 */
    static void recordGemini(Prefs p, String model, JSONObject resp) {
        JSONObject u = resp.optJSONObject("usageMetadata");
        if (u == null) return;
        record(p, "gemini", model, u.optLong("promptTokenCount"),
                u.optLong("candidatesTokenCount") + u.optLong("thoughtsTokenCount"), 0);
    }

    /** OpenAI互換の usage から記録。 */
    static void recordOpenAi(Prefs p, String e, String model, JSONObject resp) {
        JSONObject u = resp.optJSONObject("usage");
        if (u == null) return;
        record(p, e, model, u.optLong("prompt_tokens"), u.optLong("completion_tokens"), 0);
    }

    /** 設定画面・声での確認用の1行。 */
    static String line(Prefs p, String e) {
        String name = Prefs.ENGINE_SPOKEN[Prefs.engineIndex(e)];
        StringBuilder sb = new StringBuilder(name).append(": ");
        if (p.keyOf(e).isEmpty()) return sb.append("キー未登録").toString();
        sb.append("使用 ").append(money(p.spentOf(e)));
        double b = p.budgetOf(e);
        sb.append(b > 0 ? " / 上限 " + money(b) : " / 上限なし");
        sb.append(p.expiryOf(e).isEmpty() ? " / 期限なし" : " / 期限 " + p.expiryOf(e));
        String why = blocked(p, e);
        if (why != null) sb.append(" ※停止中");
        return sb.toString();
    }
}
