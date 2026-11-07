package com.ino.driveclaude;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements VoiceService.Ui {

    private static final int C_BG = 0xFF0D1117, C_FG = 0xFFE6EDF3, C_SUB = 0xFF8B949E, C_CARD = 0xFF161B22,
            C_ACC = 0xFFD97757, C_OK = 0xFF3FB950, C_WARN = 0xFFD29922, C_SPK = 0xFF388BFD, C_OFF = 0xFF30363D;
    private static final int REQ_PERM = 10;

    private Prefs prefs;
    private TextView status;
    private Button mainBtn;
    private LinearLayout logBox;
    private ScrollView logScroll;
    private boolean pendingStart;
    private Button updateBtn;
    private Updater.Info newer;
    private boolean updating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);
        buildUi();
        checkUpdate();

        if (savedInstanceState == null && prefs.autoStart() && !prefs.activeKey().isEmpty()) {
            startVoice();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        VoiceService.ui = this;
        logBox.removeAllViews();
        List<String[]> copy;
        synchronized (VoiceService.logBuf) { copy = new ArrayList<>(VoiceService.logBuf); }
        if (copy.isEmpty()) {
            addLog("sys", "「開始」を押すとハンズフリーで会話が続きます。「ストップ」で終了、「リセット」で会話を最初から。\n「明日10時に歯医者を予定に入れて」「〇〇をメモして」「もっと速く読んで」なども声でできます。");
        }
        for (String[] l : copy) addLog(l[0], l[1]);
        onState(VoiceService.instance == null ? "stopped" : VoiceService.curState,
                VoiceService.instance == null ? "タップで開始" : VoiceService.curStatus);
    }

    @Override
    protected void onPause() {
        if (VoiceService.ui == this) VoiceService.ui = null;
        super.onPause();
    }

    // ---------- UI ----------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private GradientDrawable rounded(int color, float radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private Button flatButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(C_FG);
        b.setTextSize(17);
        b.setAllCaps(false);
        b.setBackground(rounded(C_CARD, 12, C_OFF));
        b.setStateListAnimator(null);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        // header
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("🚗 ドライブClaude");
        title.setTextColor(C_FG);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button notes = flatButton("📝 記録");
        notes.setTextSize(15);
        notes.setOnClickListener(v -> startActivity(new Intent(this, NotesActivity.class)));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        nlp.rightMargin = dp(8);
        header.addView(notes, nlp);
        Button settings = flatButton("⚙ 設定");
        settings.setTextSize(15);
        settings.setOnClickListener(v -> showSettings());
        header.addView(settings, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        root.addView(header);

        // 新しい版のお知らせ（ボタン1つで更新）
        updateBtn = new Button(this);
        updateBtn.setAllCaps(false);
        updateBtn.setTextColor(Color.WHITE);
        updateBtn.setTextSize(15);
        updateBtn.setStateListAnimator(null);
        updateBtn.setBackground(rounded(0xFF1F6FEB, 12, 0));
        updateBtn.setVisibility(View.GONE);
        updateBtn.setOnClickListener(v -> startUpdate());
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        ulp.topMargin = dp(10);
        root.addView(updateBtn, ulp);

        // status
        status = new TextView(this);
        status.setTextColor(C_FG);
        status.setTextSize(24);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setGravity(Gravity.CENTER);
        status.setMinHeight(dp(64));
        status.setMaxLines(3);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        root.addView(status, slp);

        // big button
        int size = Math.min(dp(280), (int) (getResources().getDisplayMetrics().widthPixels * 0.68));
        mainBtn = new Button(this);
        mainBtn.setTextColor(Color.WHITE);
        mainBtn.setTextSize(28);
        mainBtn.setTypeface(Typeface.DEFAULT_BOLD);
        mainBtn.setAllCaps(false);
        mainBtn.setStateListAnimator(null);
        mainBtn.setOnClickListener(v -> {
            if (VoiceService.instance == null) startVoice();
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(size, size);
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        blp.topMargin = dp(8);
        blp.bottomMargin = dp(16);
        root.addView(mainBtn, blp);

        // reset / stop
        LinearLayout row = new LinearLayout(this);
        Button reset = flatButton("会話リセット");
        reset.setOnClickListener(v -> {
            if (VoiceService.instance != null) VoiceService.instance.resetHistory();
            else addLog("sys", "会話をリセットしました");
        });
        Button stop = flatButton("停止");
        stop.setOnClickListener(v -> {
            if (VoiceService.instance != null) VoiceService.instance.stopAll();
        });
        LinearLayout.LayoutParams r1 = new LinearLayout.LayoutParams(0, dp(56), 1);
        r1.rightMargin = dp(6);
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(0, dp(56), 1);
        r2.leftMargin = dp(6);
        row.addView(reset, r1);
        row.addView(stop, r2);
        root.addView(row);

        // log
        logScroll = new ScrollView(this);
        logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        logScroll.addView(logBox);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        llp.topMargin = dp(12);
        root.addView(logScroll, llp);

        setContentView(root);
    }

    private void addLog(String kind, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize("sys".equals(kind) ? 14 : 17);
        t.setTextColor("sys".equals(kind) ? C_SUB : C_FG);
        t.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        if ("me".equals(kind)) {
            t.setBackground(rounded(0x551F6FEB, 12, 0));
            t.setPadding(dp(12), dp(8), dp(12), dp(8));
            lp.gravity = Gravity.END;
            lp.leftMargin = dp(40);
        } else if ("ai".equals(kind)) {
            t.setBackground(rounded(C_CARD, 12, 0));
            t.setPadding(dp(12), dp(8), dp(12), dp(8));
            lp.gravity = Gravity.START;
            lp.rightMargin = dp(40);
        } else {
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            t.setGravity(Gravity.CENTER);
        }
        logBox.addView(t, lp);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---------- VoiceService.Ui ----------

    @Override
    public void onState(String state, String text) {
        int color;
        String label;
        switch (state) {
            case "listening": color = C_OK; label = "聞いてます"; break;
            case "thinking": color = C_WARN; label = "考え中"; break;
            case "speaking": color = C_SPK; label = "話してます"; break;
            default: color = C_ACC; label = "開始"; break;
        }
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        mainBtn.setBackground(g);
        mainBtn.setText(label);
        status.setText(text);
    }

    @Override
    public void onLog(String kind, String text) {
        addLog(kind, text);
    }

    // ---------- start / permissions ----------

    private void startVoice() {
        if (prefs.activeKey().isEmpty()) {
            showSettings();
            return;
        }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!prefs.askedOptional()) {
            // 予定・連絡先・SMSの操作用（初回だけ聞く。拒否しても会話はできる）
            prefs.setAskedOptional();
            for (String perm : new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
                    Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS}) {
                if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) need.add(perm);
            }
        }
        if (!need.isEmpty()) {
            pendingStart = true;
            requestPermissions(need.toArray(new String[0]), REQ_PERM);
            return;
        }
        synchronized (VoiceService.logBuf) { VoiceService.logBuf.clear(); }
        logBox.removeAllViews();
        startForegroundService(new Intent(this, VoiceService.class));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_PERM) return;
        boolean micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (pendingStart && micOk) {
            pendingStart = false;
            startVoice();
        } else if (!micOk) {
            pendingStart = false;
            addLog("sys", "マイクを許可しないと会話できません。設定 → アプリ → ドライブClaude → 権限 から許可してください。");
        }
    }

    // ---------- settings dialog ----------

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_SUB);
        t.setTextSize(14);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private EditText field(String value, String hint) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setTextColor(C_FG);
        e.setHintTextColor(C_SUB);
        e.setTextSize(16);
        e.setSingleLine(true);
        return e;
    }

    // ---------- update ----------

    private void checkUpdate() {
        new Thread(() -> {
            try {
                Updater.Info info = Updater.check(getApplicationContext());
                if (info != null) runOnUiThread(() -> {
                    newer = info;
                    updateBtn.setText("⬆ 新しい版があります（build " + info.build + "）タップで更新");
                    updateBtn.setVisibility(View.VISIBLE);
                });
            } catch (Exception ignored) { }
        }).start();
    }

    private void startUpdate() {
        if (newer == null || updating) return;
        if (VoiceService.instance != null) {
            addLog("sys", "会話を止めてから更新してください。");
            return;
        }
        if (!Updater.canInstall(this)) {
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("更新の許可が必要です")
                    .setMessage("このアプリがアプリを更新できるように、次の画面で「このソースを許可」をオンにして、戻ってからもう一度ボタンを押してください。（最初の1回だけです）")
                    .setPositiveButton("設定を開く", (d, w) -> startActivity(new Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("やめる", null)
                    .show();
            return;
        }
        updating = true;
        updateBtn.setEnabled(false);
        updateBtn.setText("ダウンロード中… 0%");
        final Updater.Info info = newer;
        new Thread(() -> {
            try {
                Updater.downloadAndInstall(getApplicationContext(), info,
                        pct -> runOnUiThread(() -> updateBtn.setText("ダウンロード中… " + pct + "%")));
                runOnUiThread(() -> updateBtn.setText("インストール画面で「更新」を押してください"));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    updateBtn.setText("⬆ 更新に失敗しました。タップでやり直し");
                    addLog("sys", "更新エラー: " + e.getMessage());
                });
            } finally {
                runOnUiThread(() -> { updating = false; updateBtn.setEnabled(true); });
            }
        }).start();
    }

    // ---------- settings dialog ----------

    private void showGuideMenu() {
        final String[] pages = {"start", "claude", "gemini", "openai", "grok"};
        final String[] names = {"📖 はじめに：どのAIにする？／止まる仕組み", "Claude の登録方法", "Gemini の登録方法", "ChatGPT(OpenAI) の登録方法", "Grok(xAI) の登録方法"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("登録ガイド（図解）")
                .setItems(names, (d, w) -> startActivity(new Intent(this, HelpActivity.class).putExtra("page", pages[w])))
                .setNegativeButton("閉じる", null)
                .show();
    }

    /** 1つのAIの設定（キー・モデル・上限額・期限）。 */
    private void showEngineSettings(String e) {
        final int ei = Prefs.engineIndex(e);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(8));

        TextView state = new TextView(this);
        state.setTextColor(C_FG);
        state.setTextSize(14);
        state.setText(Budget.line(prefs, e) + "\n※使用額はトークン数からの見積もり（目安）です。正確な残高は各社の管理画面で確認してください。");
        box.addView(state);

        box.addView(label("APIキー"));
        EditText key = field(prefs.keyOf(e), "claude".equals(e) ? "sk-ant-api03-..." : "gemini".equals(e) ? "AIza..." : "grok".equals(e) ? "xai-..." : "sk-...");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(key);
        TextView kn = new TextView(this);
        kn.setText("キーはこのスマホの中だけに保存されます。人に見せたりチャットに貼ったりしないでください。");
        kn.setTextColor(C_SUB);
        kn.setTextSize(12);
        box.addView(kn);

        box.addView(label("モデル"));
        RadioGroup models = new RadioGroup(this);
        String[] ids = Prefs.idsOf(e), labels = Prefs.labelsOf(e);
        int checked = 0;
        for (int i = 0; i < ids.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(1000 + i);
            rb.setText(labels[i]);
            rb.setTextColor(C_FG);
            models.addView(rb);
            if (ids[i].equals(prefs.presetOf(e))) checked = i;
        }
        models.check(1000 + checked);
        box.addView(models);
        EditText custom = null;
        if (!"claude".equals(e)) {
            box.addView(label("モデル名を自分で指定（任意。入れるとこちら優先）"));
            custom = field(prefs.customOf(e), "例: " + ids[0]);
            box.addView(custom);
            TextView cn = new TextView(this);
            cn.setText("自分で指定したモデルの料金は、1M文字あたり入力$2・出力$10で見積もります。");
            cn.setTextColor(C_SUB);
            cn.setTextSize(12);
            box.addView(cn);
        }

        box.addView(label("上限額（ドル・累計。0なら上限なし）"));
        EditText budget = field(prefs.budgetOf(e) == 0 ? "0" : String.format(java.util.Locale.US, "%.2f", prefs.budgetOf(e)), "例: 5");
        budget.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(budget);
        TextView bn = new TextView(this);
        bn.setText("追加したクレジットの額に合わせてください。使った額がここに達するとこのAIは止まります。");
        bn.setTextColor(C_SUB);
        bn.setTextSize(12);
        box.addView(bn);

        box.addView(label("期限日（yyyy-MM-dd。空なら期限なし）"));
        EditText expiry = field(prefs.expiryOf(e), "例: " + java.time.LocalDate.now().plusDays(90));
        expiry.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(expiry);
        TextView en = new TextView(this);
        en.setText("無料クレジットや、クレジットの失効日（購入から1年など）に合わせて入れます。この日を過ぎると止まります。");
        en.setTextColor(C_SUB);
        en.setTextSize(12);
        box.addView(en);

        CheckBox reset = new CheckBox(this);
        reset.setText("使った額を0に戻す（クレジットを入れ直したとき）");
        reset.setTextColor(C_FG);
        box.addView(reset);

        Button guide = flatButton("📖 " + Prefs.ENGINE_SPOKEN[ei] + " の登録方法（図解）");
        guide.setTextSize(14);
        guide.setOnClickListener(v -> startActivity(new Intent(this, HelpActivity.class).putExtra("page", e)));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        glp.topMargin = dp(10);
        box.addView(guide, glp);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        final EditText fCustom = custom;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(Prefs.ENGINE_NAMES[ei])
                .setView(sv)
                .setPositiveButton("保存", (d, w) -> {
                    int idx = models.getCheckedRadioButtonId() - 1000;
                    if (idx < 0 || idx >= ids.length) idx = 0;
                    String k = key.getText().toString().trim();
                    prefs.saveEngine(e, k, ids[idx], fCustom == null ? "" : fCustom.getText().toString());
                    double b = 0;
                    try { b = Double.parseDouble(budget.getText().toString().trim()); } catch (Exception ignored) { }
                    String ex = expiry.getText().toString().trim();
                    boolean badDate = !ex.isEmpty() && !Prefs.validDate(ex);
                    prefs.saveLimit(e, b, badDate ? "" : ex);
                    if (reset.isChecked()) prefs.resetSpent(e);
                    if ("claude".equals(e) && !k.isEmpty() && !k.startsWith("sk-ant-api")) {
                        addLog("sys", "注意: Anthropicのキーは通常 sk-ant-api03- で始まります。console.anthropic.com の API Keys で発行したキーか確認してください。");
                    } else if (badDate) {
                        addLog("sys", "期限日の形式が違うので期限なしにしました。2027-03-31 のように入れてください。");
                    } else {
                        addLog("sys", Prefs.ENGINE_SPOKEN[ei] + "の設定を保存しました。" + Budget.line(prefs, e));
                    }
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(8));

        box.addView(label("使うAI（声で「ChatGPTに切り替えて」でも変えられます）"));
        RadioGroup engineGroup = new RadioGroup(this);
        for (int i = 0; i < Prefs.ENGINES.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(2000 + i);
            rb.setText(Prefs.ENGINE_NAMES[i]);
            rb.setTextColor(C_FG);
            engineGroup.addView(rb);
        }
        engineGroup.check(2000 + Prefs.engineIndex(prefs.engine()));
        box.addView(engineGroup);

        box.addView(label("AIごとの設定（キー・モデル・上限額・期限）"));
        for (int i = 0; i < Prefs.ENGINES.length; i++) {
            final String e = Prefs.ENGINES[i];
            Button b = flatButton("🔑 " + Budget.line(prefs, e));
            b.setTextSize(12);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setPadding(dp(12), 0, dp(12), 0);
            b.setOnClickListener(v -> showEngineSettings(e));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
            lp.topMargin = dp(6);
            box.addView(b, lp);
        }
        TextView limNote = new TextView(this);
        limNote.setText("上限額か期限に達したAIは自動で止まります（止まったことは声でも知らせます）。");
        limNote.setTextColor(C_SUB);
        limNote.setTextSize(12);
        box.addView(limNote);
        Button help = flatButton("📖 登録ガイド（図解）");
        help.setTextSize(14);
        help.setOnClickListener(v -> showGuideMenu());
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        hlp.topMargin = dp(8);
        box.addView(help, hlp);

        CheckBox web = new CheckBox(this);
        web.setText("Web検索を使う（Claude・Geminiのみ）");
        web.setTextColor(C_FG);
        web.setChecked(prefs.webSearch());
        box.addView(web);

        CheckBox auto = new CheckBox(this);
        auto.setText("アプリを開いたら自動で会話を開始");
        auto.setTextColor(C_FG);
        auto.setChecked(prefs.autoStart());
        box.addView(auto);

        final TextView rateLabel = label("");
        box.addView(rateLabel);
        SeekBar rate = new SeekBar(this);
        rate.setMax(10); // 0.8 〜 1.8
        rate.setProgress(Math.round((prefs.rate() - 0.8f) * 10));
        rateLabel.setText(String.format("読み上げ速度: %.1f", 0.8f + rate.getProgress() / 10f));
        rate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                rateLabel.setText(String.format("読み上げ速度: %.1f", 0.8f + p / 10f));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(rate);

        box.addView(label("現在地（検索のヒント）"));
        EditText city = field(prefs.city(), "例: 名古屋");
        box.addView(city);

        box.addView(label("追加の指示（任意）"));
        EditText extra = field(prefs.extra(), "例: 返事は関西弁で");
        extra.setSingleLine(false);
        extra.setMinLines(2);
        box.addView(extra);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("設定")
                .setView(sv)
                .setPositiveButton("保存", (d, w) -> {
                    prefs.save(web.isChecked(), auto.isChecked(), 0.8f + rate.getProgress() / 10f,
                            city.getText().toString(), extra.getText().toString());
                    int ei = engineGroup.getCheckedRadioButtonId() - 2000;
                    if (ei < 0 || ei >= Prefs.ENGINES.length) ei = 0;
                    String chosen = Prefs.ENGINES[ei];
                    prefs.setEngine(chosen);
                    String why = Budget.blocked(prefs, chosen);
                    if (prefs.keyOf(chosen).isEmpty()) {
                        addLog("sys", Prefs.ENGINE_SPOKEN[ei] + "を使うには、「AIごとの設定」でAPIキーを入れてください。");
                    } else if (why != null) {
                        addLog("sys", why);
                    } else {
                        addLog("sys", "設定を保存しました（使うAI: " + Prefs.ENGINE_SPOKEN[ei] + "）");
                    }
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }
}
