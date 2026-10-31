package com.ino.driveclaude;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** 会話のまとめ・メモ・記憶を見る画面。長押しで削除。 */
public class NotesActivity extends Activity {

    private static final int C_BG = 0xFF0D1117, C_FG = 0xFFE6EDF3, C_SUB = 0xFF8B949E, C_CARD = 0xFF161B22,
            C_ACC = 0xFFD97757, C_OFF = 0xFF30363D;
    private static final String[] TABS = {"まとめ", "メモ", "記憶"};
    private static final String[] FILES = {Store.SESSIONS, Store.MEMOS, Store.MEMORY};

    private int tab = 0;
    private final Button[] tabBtns = new Button[3];
    private LinearLayout list;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(12));
        return g;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("📝 記録");
        title.setTextColor(C_FG);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        LinearLayout tabs = new LinearLayout(this);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(TABS[i]);
            b.setAllCaps(false);
            b.setTextSize(16);
            b.setStateListAnimator(null);
            b.setOnClickListener(v -> { tab = idx; refresh(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
            lp.setMargins(dp(3), dp(12), dp(3), dp(4));
            tabs.addView(b, lp);
            tabBtns[i] = b;
        }
        root.addView(tabs);

        TextView hint = new TextView(this);
        hint.setText("長押しで削除");
        hint.setTextColor(C_SUB);
        hint.setTextSize(12);
        hint.setGravity(Gravity.END);
        root.addView(hint);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < 3; i++) {
            tabBtns[i].setBackground(rounded(i == tab ? C_ACC : C_CARD));
            tabBtns[i].setTextColor(C_FG);
        }
        list.removeAllViews();
        JSONArray a = Store.load(this, FILES[tab]);
        if (a.length() == 0) {
            TextView t = new TextView(this);
            t.setText(tab == 0 ? "まだまとめはありません。2往復以上会話して「ストップ」すると自動で作られます。"
                    : tab == 1 ? "メモはありません。会話中に「〇〇をメモして」と言うと保存されます。"
                    : "記憶はありません。「〇〇を覚えて」と言うか、会話のまとめから自動で増えます。");
            t.setTextColor(C_SUB);
            t.setPadding(0, dp(16), 0, 0);
            list.addView(t);
            return;
        }
        // 新しい順に表示
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            final int idx = i;
            TextView t = new TextView(this);
            t.setTextColor(C_FG);
            t.setTextSize(16);
            t.setLineSpacing(0, 1.2f);
            t.setBackground(rounded(C_CARD));
            t.setPadding(dp(14), dp(10), dp(14), dp(10));
            if (tab == 0) {
                t.setText(o.optString("time") + "\n" + o.optString("title") + "\n" + o.optString("summary"));
                t.setOnClickListener(v -> showSession(o));
            } else {
                t.setText(o.optString("text") + "\n" + o.optString("time"));
            }
            t.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setMessage("削除しますか？")
                        .setPositiveButton("削除", (d, w) -> { Store.removeAt(this, FILES[tab], idx); refresh(); })
                        .setNegativeButton("キャンセル", null)
                        .show();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            list.addView(t, lp);
        }
    }

    private static String bullets(JSONArray a) {
        StringBuilder sb = new StringBuilder();
        if (a != null) for (int i = 0; i < a.length(); i++) sb.append("・").append(a.optString(i)).append('\n');
        return sb.toString();
    }

    private void showSession(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        sb.append(o.optString("time")).append("\n\n【要点】\n").append(o.optString("summary")).append("\n");
        String dec = bullets(o.optJSONArray("decisions"));
        if (!dec.isEmpty()) sb.append("\n【決めたこと】\n").append(dec);
        String todo = bullets(o.optJSONArray("todos"));
        if (!todo.isEmpty()) sb.append("\n【やること】\n").append(todo);
        JSONArray tr = o.optJSONArray("transcript");
        if (tr != null && tr.length() > 0) {
            sb.append("\n【会話全文】\n");
            for (int i = 0; i < tr.length(); i++) {
                JSONObject l = tr.optJSONObject(i);
                if (l != null) sb.append(l.optString("who")).append(": ").append(l.optString("text")).append('\n');
            }
        }
        final String full = o.optString("title") + "\n" + sb;

        TextView t = new TextView(this);
        t.setText(sb.toString());
        t.setTextColor(C_FG);
        t.setTextSize(15);
        t.setTextIsSelectable(true);
        t.setPadding(dp(20), dp(8), dp(20), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(o.optString("title"))
                .setView(sv)
                .setPositiveButton("共有", (d, w) -> {
                    Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, full);
                    startActivity(Intent.createChooser(s, "まとめを共有"));
                })
                .setNegativeButton("閉じる", null)
                .show();
    }
}
