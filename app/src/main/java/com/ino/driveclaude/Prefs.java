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
    boolean webSearch() { return sp.getBoolean("webSearch", true); }
    boolean autoStart() { return sp.getBoolean("autoStart", false); }
    float rate() { return sp.getFloat("rate", 1.2f); }
    String city() { return sp.getString("city", "名古屋").trim(); }
    String extra() { return sp.getString("extra", "").trim(); }
    boolean askedOptional() { return sp.getBoolean("askedOptional", false); }

    String engine() { return sp.getString("engine", "claude"); }
    boolean gemini() { return "gemini".equals(engine()); }
    String geminiKey() { return sp.getString("geminiKey", "").trim(); }
    String geminiModel() { return sp.getString("geminiModel", GEMINI_IDS[0]); }
    /** いま使うAIのAPIキー。 */
    String activeKey() { return gemini() ? geminiKey() : apiKey(); }
    String engineLabel() { return gemini() ? "Gemini(" + geminiModel() + ")" : "Claude " + modelShort(); }
    void setEngine(String e) { sp.edit().putString("engine", e).apply(); }
    void saveGemini(String engine, String key, String model) {
        sp.edit().putString("engine", engine).putString("geminiKey", key.trim()).putString("geminiModel", model).apply();
    }

    String modelShort() {
        for (int i = 0; i < MODEL_IDS.length; i++) if (MODEL_IDS[i].equals(model())) return MODEL_SHORT[i];
        return model();
    }

    void setModel(String id) { sp.edit().putString("model", id).apply(); }
    void setRate(float r) { sp.edit().putFloat("rate", Math.max(0.6f, Math.min(2.0f, r))).apply(); }
    void setWebSearch(boolean b) { sp.edit().putBoolean("webSearch", b).apply(); }
    void setAskedOptional() { sp.edit().putBoolean("askedOptional", true).apply(); }

    void save(String apiKey, String model, boolean webSearch, boolean autoStart, float rate, String city, String extra) {
        sp.edit()
                .putString("apiKey", apiKey.trim())
                .putString("model", model)
                .putBoolean("webSearch", webSearch)
                .putBoolean("autoStart", autoStart)
                .putFloat("rate", rate)
                .putString("city", city.trim())
                .putString("extra", extra.trim())
                .apply();
    }
}
