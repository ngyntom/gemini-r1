package vn.r1.geminibot;

import android.util.Log;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Log + trạng thái hiện tại của bot, để Web UI hiển thị. */
final class BotLog {
    private static final String TAG = BotService.TAG;
    private static final int MAX_LINES = 200;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    /** starting | no_key | idle | listening | thinking | speaking | paused | error */
    static volatile String state = "starting";
    static volatile String wakeName = "";
    static volatile String lastHeard = "";
    static volatile String lastReply = "";
    /** Chẩn đoán: RMS micro lớn nhất ~1 s gần nhất, điểm từ đánh thức lớn nhất ~1 s gần nhất. */
    static volatile int micLevel;
    static volatile float wakeScore;

    private BotLog() {}

    static void i(String msg) {
        Log.i(TAG, msg);
        add("I", msg);
    }

    static void w(String msg) {
        Log.w(TAG, msg);
        add("W", msg);
    }

    static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        add("E", t == null ? msg : msg + ": " + t);
    }

    private static synchronized void add(String level, String msg) {
        lines.addLast(TIME.format(new Date()) + " " + level + " " + msg);
        while (lines.size() > MAX_LINES) lines.removeFirst();
    }

    static synchronized JSONArray tail() {
        JSONArray a = new JSONArray();
        for (String l : lines) a.put(l);
        return a;
    }
}
