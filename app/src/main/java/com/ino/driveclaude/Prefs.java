package com.ino.driveclaude;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String[] MODEL_IDS = {"claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"};
    static final String[] MODEL_LABELS = {"Haiku 5.5（速い・安い／おすすめ）", "Sonnet 5.5（賢い）", "Opus 5.5（最高性能・遅め）"};
    static final String[] MODEL_SHORT = {"Haiku", "Sonnet", "Opus"};
    static final String[] GEMINI_IDS = {"gemini-3.8-flash", "gemini-3.6-flash"};
    static final String[] GEMINI_LABELS = {"Gemini 3.8 Flash（おすすめ）", "Gemini 3.6 Flash"};

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("drive_claude", Context.MODE_PRIVATE);
    }

    String apiKey() { return sp.getString("apiKey", "").trim(); }
    String model() { return sp.getString("model", MODEL_IDS[0]); }
    boolean webSearch() { return sp.getBoolean("webSearch", false); }
    boolean autoStart() { return sp.getBoolean("autoStart", false); }
    float rate() { return sp.getFloat("rate", 1.2f); }
    String city() { return sp.getString("city", "名古屋").trim(); }
    String extra() { return sp.getString("extra", "").trim(); }
    boolean askedOptional() { return sp.getBoolean("askedOptional", false); }

    // ---- GeminiのWeb検索回数（月ごと。無料枠は月5,000回）----
    static final int SEARCH_FREE_PER_MONTH = 5000;
    static final int SEARCH_STOP_AT = 4800;

    String searchMonth() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.JAPAN);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
        return f.format(new java.util.Date());
    }

    int searchCount() {
        return searchMonth().equals(sp.getString("searchMonth", "")) ? sp.getInt("searchCount", 0) : 0;
    }

    void addSearch(int n) {
        sp.edit().putString("searchMonth", searchMonth()).putInt("searchCount", searchCount() + Math.max(1, n)).apply();
    }

    // ---------- AIの種類（claude / gemini / openai / grok）----------
    static final String[] ENGINES = {"claude", "gemini", "openai", "grok"};
    static final String[] ENGINE_NAMES = {"Claude（Anthropic）", "Gemini（Google）", "ChatGPT（OpenAI）", "Grok（xAI）"};
    static final String[] ENGINE_SPOKEN = {"Claude", "Gemini", "ChatGPT", "Grok"};
    static final String[] OPENAI_IDS = {"gpt-6-luna", "gpt-6.1-sol", "gpt-6-astra"};
    static final String[] OPENAI_LABELS = {"GPT-6 Luna（安い／おすすめ）", "GPT-6.1 Sol（賢い）", "GPT-6 Astra（最高性能・高い）"};
    static final String[] GROK_IDS = {"grok-4-1-fast", "grok-4.5"};
    static final String[] GROK_LABELS = {"Grok 4.1 Fast（安い／おすすめ）", "Grok 4.5（賢い）"};
    // 料金の目安（USD / 100万トークン：入力, 出力）。安全のため少し高めに見積もる
    static final double[][] CLAUDE_PRICE = {{0.10, 0.50}, {2, 10}, {4, 20}};
    static final double[][] GEMINI_PRICE = {{1.5, 7.5}, {1.5, 7.5}};
    static final double[][] OPENAI_PRICE = {{0.10, 0.50}, {2, 10}, {10, 50}};
    static final double[][] GROK_PRICE = {{1.25, 2.5}, {2, 6}};
    static final double[] UNKNOWN_PRICE = {2, 10}; // 自分で入力したモデルは、この料金で見積もる
    static final String YEN_NOTE = "1ドル=150円で換算";

    static boolean validDate(String s) { return s != null && s.matches("\\d{4}-\\d{2}-\\d{2}"); }

    static int engineIndex(String e) {
        for (int i = 0; i < ENGINES.length; i++) if (ENGINES[i].equals(e)) return i;
        return 0;
    }

    /** 声や文字から AI の種類を判定。わからなければ null。 */
    static String parseEngine(String s) {
        String t = s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
        if (t.startsWith("gem") || t.contains("ジェミニ")) return "gemini";
        if (t.startsWith("open") || t.contains("gpt") || t.contains("chat") || t.contains("チャット")) return "openai";
        if (t.startsWith("grok") || t.startsWith("xai") || t.contains("グロック")) return "grok";
        if (t.startsWith("claude") || t.contains("クロード")) return "claude";
        return null;
    }

    // ---- ランニングモード ----
    float runKm() { return sp.getFloat("runKm", 1.0f); }
    int hrMax() { return sp.getInt("hrMax", 0); } // 0 = 心拍の警告なし
    void saveRun(float km, int hrMax) {
        sp.edit().putFloat("runKm", Math.max(0.25f, Math.min(5f, km))).putInt("hrMax", Math.max(0, Math.min(230, hrMax))).apply();
    }

    String engine() { return sp.getString("engine", "gemini"); }
    boolean gemini() { return "gemini".equals(engine()); }
    void setEngine(String e) { sp.edit().putString("engine", e).apply(); }

    String keyOf(String e) {
        switch (e) {
            case "gemini": return sp.getString("geminiKey", "").trim();
            case "openai": return sp.getString("openaiKey", "").trim();
            case "grok": return sp.getString("grokKey", "").trim();
            default: return sp.getString("apiKey", "").trim();
        }
    }

    static String[] idsOf(String e) {
        switch (e) {
            case "gemini": return GEMINI_IDS;
            case "openai": return OPENAI_IDS;
            case "grok": return GROK_IDS;
            default: return MODEL_IDS;
        }
    }

    static String[] labelsOf(String e) {
        switch (e) {
            case "gemini": return GEMINI_LABELS;
            case "openai": return OPENAI_LABELS;
            case "grok": return GROK_LABELS;
            default: return MODEL_LABELS;
        }
    }

    static double[][] pricesOf(String e) {
        switch (e) {
            case "gemini": return GEMINI_PRICE;
            case "openai": return OPENAI_PRICE;
            case "grok": return GROK_PRICE;
            default: return CLAUDE_PRICE;
        }
    }

    /** 選んだモデルID（自分で入力したIDがあればそれ）。 */
    String modelOf(String e) {
        String custom = sp.getString("custom_" + e, "").trim();
        if (!custom.isEmpty()) return custom;
        return presetOf(e);
    }

    String presetOf(String e) {
        String key = "claude".equals(e) ? "model" : e + "Model";
        return sp.getString(key, idsOf(e)[0]);
    }

    String customOf(String e) { return sp.getString("custom_" + e, "").trim(); }

    void saveEngine(String e, String key, String preset, String custom) {
        SharedPreferences.Editor ed = sp.edit();
        ed.putString("claude".equals(e) ? "apiKey" : e + "Key", key.trim());
        ed.putString("claude".equals(e) ? "model" : e + "Model", preset);
        ed.putString("custom_" + e, custom.trim());
        ed.apply();
    }

    /** 1M トークンあたりの料金 {入力, 出力}。 */
    double[] priceOf(String e, String modelId) {
        String[] ids = idsOf(e);
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(modelId)) return pricesOf(e)[i];
        return UNKNOWN_PRICE;
    }

    // ---- 上限額（USD、累計）と期限日、使った額 ----
    double budgetOf(String e) { return sp.getFloat("budget_" + e, "gemini".equals(e) ? 0f : 5f); }   // 0 = 上限なし
    String expiryOf(String e) { return sp.getString("expiry_" + e, "").trim(); } // yyyy-MM-dd、空 = 期限なし
    double spentOf(String e) { return sp.getFloat("spent_" + e, 0f); }

    void saveLimit(String e, double budget, String expiry) {
        sp.edit().putFloat("budget_" + e, (float) Math.max(0, budget)).putString("expiry_" + e, expiry.trim()).apply();
    }

    void addSpent(String e, double usd) {
        sp.edit().putFloat("spent_" + e, (float) (spentOf(e) + usd)).apply();
    }

    void resetSpent(String e) { sp.edit().putFloat("spent_" + e, 0f).apply(); }

    /** いま使うAIのAPIキー。 */
    String activeKey() { return keyOf(engine()); }
    String engineLabel() {
        String e = engine();
        return "claude".equals(e) ? "Claude " + modelShort() : ENGINE_SPOKEN[engineIndex(e)] + "(" + modelOf(e) + ")";
    }

    String modelShort() {
        for (int i = 0; i < MODEL_IDS.length; i++) if (MODEL_IDS[i].equals(model())) return MODEL_SHORT[i];
        return model();
    }

    void setModel(String id) { sp.edit().putString("model", id).apply(); }
    void setRate(float r) { sp.edit().putFloat("rate", Math.max(0.6f, Math.min(2.0f, r))).apply(); }
    void setWebSearch(boolean b) { sp.edit().putBoolean("webSearch", b).apply(); }
    void setAskedOptional() { sp.edit().putBoolean("askedOptional", true).apply(); }

    void save(boolean webSearch, boolean autoStart, float rate, String city, String extra) {
        sp.edit()
                .putBoolean("webSearch", webSearch)
                .putBoolean("autoStart", autoStart)
                .putFloat("rate", rate)
                .putString("city", city.trim())
                .putString("extra", extra.trim())
                .apply();
    }
}
