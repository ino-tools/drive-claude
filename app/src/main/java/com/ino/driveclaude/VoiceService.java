package com.ino.driveclaude;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * 「聞く → Claudeに聞く → 読み上げる → また聞く」のループを回す前面サービス。
 * 前面サービスにしているので、ナビなど他のアプリを表示中も会話を続けられる。
 */
public class VoiceService extends Service implements RecognitionListener, TextToSpeech.OnInitListener {

    interface Ui {
        void onState(String state, String status);
        void onLog(String kind, String text);
    }

    static final String ACTION_STOP = "com.ino.driveclaude.STOP";
    static final String ACTION_RESET = "com.ino.driveclaude.RESET";
    private static final String CHANNEL = "voice";
    private static final int NOTIF_ID = 1;
    private static final int MAX_HISTORY = 20;

    private static final Pattern STOP_WORDS = Pattern.compile("^(ストップ|停止|終わり|おわり|終了|おしまい)[。!！]?$");
    private static final Pattern RESET_WORDS = Pattern.compile("^(リセット|会話をリセット|最初から)[。!！]?$");

    static volatile VoiceService instance;
    static volatile Ui ui;
    static final List<String[]> logBuf = new ArrayList<>();
    static volatile String curState = "stopped";
    static volatile String curStatus = "タップで開始";

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private SpeechRecognizer sr;
    private TextToSpeech tts;
    private boolean ttsReady;
    private ToneGenerator tone;
    private JSONArray history = new JSONArray();
    private boolean running;
    private boolean stopAfterSpeech;
    private int silent;
    private int uttSeq;
    private final List<String[]> transcript = java.util.Collections.synchronizedList(new ArrayList<>());
    private long sessionStartMs;

    // ---------- lifecycle ----------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = new Prefs(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "音声会話", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
        } catch (RuntimeException e) {
            tone = null;
        }
        running = true;
        setState("thinking", "準備中…");
        createRecognizer();
        tts = new TextToSpeech(this, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopAll();
        } else if (intent != null && ACTION_RESET.equals(intent.getAction())) {
            resetHistory();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        if (sr != null) { try { sr.destroy(); } catch (Exception ignored) { } sr = null; }
        if (tts != null) { try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { } tts = null; }
        if (tone != null) { tone.release(); tone = null; }
        ex.shutdownNow();
        instance = null;
        setState("stopped", "タップで開始");
        super.onDestroy();
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, VoiceService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("ドライブClaude")
                .setContentText("音声会話中（「ストップ」で終了）")
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat), "停止", stop).build())
                .build();
    }

    // ---------- public controls ----------

    void resetHistory() {
        history = new JSONArray();
        log("sys", "会話をリセットしました");
    }

    void stopAll() {
        if (running) summarizeAsync();
        running = false;
        h.removeCallbacksAndMessages(null);
        if (sr != null) { try { sr.cancel(); } catch (Exception ignored) { } }
        if (tts != null) { try { tts.stop(); } catch (Exception ignored) { } }
        setState("stopped", "タップで開始");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ---------- UI bridge ----------

    private void setState(String state, String status) {
        curState = state;
        curStatus = status;
        Ui u = ui;
        if (u != null) h.post(() -> u.onState(state, status));
    }

    private void log(String kind, String text) {
        synchronized (logBuf) {
            logBuf.add(new String[]{kind, text});
            while (logBuf.size() > 100) logBuf.remove(0);
        }
        Ui u = ui;
        if (u != null) h.post(() -> u.onLog(kind, text));
    }

    private void beep(int type, int ms) {
        if (tone != null) {
            try { tone.startTone(type, ms); } catch (Exception ignored) { }
        }
    }

    // ---------- TTS ----------

    @Override
    public void onInit(int status) {
        ttsReady = status == TextToSpeech.SUCCESS;
        if (ttsReady) {
            tts.setLanguage(Locale.JAPAN);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { h.post(VoiceService.this::afterSpeech); }
                @Override public void onError(String id) { h.post(VoiceService.this::afterSpeech); }
            });
        } else {
            log("sys", "読み上げ機能を初期化できませんでした。返事は画面に表示します。");
        }
        if (prefs.activeKey().isEmpty()) {
            log("sys", "APIキーが未設定です。設定から入力してください。");
            stopAll();
            return;
        }
        listen();
    }

    private void speak(String text) {
        String t = Claude.forSpeech(text);
        if (!ttsReady || tts == null || t.isEmpty()) {
            h.postDelayed(this::afterSpeech, 300);
            return;
        }
        setState("speaking", "");
        tts.setSpeechRate(prefs.rate());
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u" + (++uttSeq));
    }

    private void afterSpeech() {
        if (stopAfterSpeech) { stopAll(); return; }
        if (running) listen();
    }

    // ---------- speech recognition ----------

    private void createRecognizer() {
        if (sr != null) { try { sr.destroy(); } catch (Exception ignored) { } }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            log("sys", "この端末では音声認識が使えません。Googleアプリが入っているか確認してください。");
            sr = null;
            return;
        }
        sr = SpeechRecognizer.createSpeechRecognizer(this);
        sr.setRecognitionListener(this);
    }

    private void listen() {
        if (!running) return;
        if (sr == null) createRecognizer();
        if (sr == null) { stopAll(); return; }
        setState("listening", "話しかけてください");
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try {
            sr.startListening(i);
        } catch (Exception e) {
            createRecognizer();
            h.postDelayed(this::listen, 800);
        }
    }

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onEvent(int eventType, Bundle params) { }

    @Override
    public void onPartialResults(Bundle b) {
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r != null && !r.isEmpty() && !r.get(0).isEmpty()) setState("listening", r.get(0));
    }

    @Override
    public void onResults(Bundle b) {
        if (!running) return;
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String text = (r == null || r.isEmpty()) ? "" : r.get(0).trim();
        if (text.isEmpty()) { onSilence(); return; }
        handleUtterance(text);
    }

    @Override
    public void onError(int error) {
        if (!running) return;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                onSilence();
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                log("sys", "マイクの使用が許可されていません。アプリの権限で許可してください。");
                stopAll();
                break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                setState("listening", "電波待ち…");
                h.postDelayed(this::listen, 2000);
                break;
            default:
                // ERROR_RECOGNIZER_BUSY / ERROR_CLIENT など: 作り直して再開
                createRecognizer();
                h.postDelayed(this::listen, 800);
                break;
        }
    }

    private void onSilence() {
        silent++;
        h.postDelayed(this::listen, silent > 3 ? 1200 : 200);
    }

    // ---------- conversation ----------

    private void handleUtterance(String text) {
        silent = 0;
        log("me", text);
        if (transcript.isEmpty()) sessionStartMs = System.currentTimeMillis();
        transcript.add(new String[]{"ユーザー", text});

        if (STOP_WORDS.matcher(text).matches()) {
            stopAfterSpeech = true;
            speak("終了します。");
            return;
        }
        if (RESET_WORDS.matcher(text).matches()) {
            resetHistory();
            speak("リセットしました。");
            return;
        }

        try {
            history.put(new JSONObject().put("role", "user").put("content", text));
            while (history.length() > MAX_HISTORY) history.remove(0);
            while (history.length() > 0 && !"user".equals(history.getJSONObject(0).optString("role"))) history.remove(0);
        } catch (Exception ignored) { }

        setState("thinking", "考え中…");
        beep(ToneGenerator.TONE_PROP_BEEP, 80);
        final JSONArray snapshot;
        try {
            snapshot = new JSONArray(history.toString());
        } catch (Exception e) {
            return;
        }
        ex.submit(() -> {
            try {
                String reply = Claude.ask(getApplicationContext(), prefs, snapshot);
                if (reply.isEmpty()) reply = "すみません、うまく答えられませんでした。";
                final String fr = reply;
                h.post(() -> {
                    if (!running) return;
                    try { history.put(new JSONObject().put("role", "assistant").put("content", fr)); } catch (Exception ignored) { }
                    log("ai", fr);
                    transcript.add(new String[]{"Claude", fr});
                    speak(fr);
                });
            } catch (Claude.ApiException e) {
                h.post(() -> {
                    dropLastUser();
                    log("sys", "APIエラー(" + e.code + "): " + e.getMessage());
                    if (e.code == 401 || e.code == 403) {
                        stopAfterSpeech = true;
                        speak("APIキーが正しくないようです。設定を確認してください。");
                    } else if (e.code == 400 && e.getMessage() != null && e.getMessage().contains("credit")) {
                        stopAfterSpeech = true;
                        speak("APIのクレジットが足りません。");
                    } else if (e.code == 429) {
                        speak("利用上限に達しました。少し待ってからもう一度どうぞ。");
                    } else {
                        speak("エラーが起きました。もう一度どうぞ。");
                    }
                });
            } catch (Exception e) {
                h.post(() -> {
                    dropLastUser();
                    log("sys", "通信エラー: " + e.getClass().getSimpleName() + " " + e.getMessage());
                    speak("通信できませんでした。もう一度どうぞ。");
                });
            }
        });
    }

    /** 会話が2往復以上あれば、終了時にまとめを作って保存する（記憶も更新）。 */
    private void summarizeAsync() {
        final List<String[]> copy;
        final long startMs;
        synchronized (transcript) {
            int turns = 0;
            for (String[] t : transcript) if ("ユーザー".equals(t[0])) turns++;
            if (turns < 2 || prefs.activeKey().isEmpty()) return;
            copy = new ArrayList<>(transcript);
            transcript.clear();
            startMs = sessionStartMs > 0 ? sessionStartMs : System.currentTimeMillis();
            sessionStartMs = 0;
        }
        final android.content.Context app = getApplicationContext();
        log("sys", "会話のまとめを作成中…");
        new Thread(() -> log("sys", saveSummary(app, copy, startMs))).start();
    }

    /** まとめを作り、記録と記憶に保存して結果メッセージを返す。 */
    private static String saveSummary(android.content.Context app, List<String[]> copy, long startMs) {
        Prefs pr = new Prefs(app);
        StringBuilder sb = new StringBuilder();
        JSONArray tr = new JSONArray();
        for (String[] t : copy) {
            sb.append(t[0]).append(": ").append(t[1]).append('\n');
            try { tr.put(new JSONObject().put("who", t[0]).put("text", t[1])); } catch (Exception ignored) { }
        }
        try {
            JSONObject r = Claude.summarize(app, pr, sb.toString());
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN);
            JSONObject session = new JSONObject()
                    .put("time", f.format(new java.util.Date(startMs)))
                    .put("title", r.optString("title", "会話"))
                    .put("summary", r.optString("summary"))
                    .put("decisions", r.optJSONArray("decisions") == null ? new JSONArray() : r.optJSONArray("decisions"))
                    .put("todos", r.optJSONArray("todos") == null ? new JSONArray() : r.optJSONArray("todos"))
                    .put("transcript", tr);
            Store.addSession(app, session);
            JSONArray facts = r.optJSONArray("facts");
            if (facts != null) for (int i = 0; i < facts.length(); i++) {
                String fct = facts.optString(i).trim();
                if (!fct.isEmpty()) Store.addMemory(app, fct);
            }
            return "会話のまとめを保存しました（📝記録 で見られます）";
        } catch (Exception e) {
            return "まとめの作成に失敗しました: " + e.getMessage();
        }
    }

    private void dropLastUser() {
        try {
            int last = history.length() - 1;
            if (last >= 0 && "user".equals(history.getJSONObject(last).optString("role"))) history.remove(last);
        } catch (Exception ignored) { }
    }
}
