package app.aoki.yuki.wirelesstntn;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Process-wide log sink: mirrors everything to logcat and keeps a bounded in-memory buffer
 * so the UI can render (and re-render, after a configuration change) the same stream.
 *
 * The listener is keyed by an owner token.  During an Activity recreation the new instance's
 * onCreate() runs before the old instance's onDestroy(), so an unconditional clear would wipe
 * the listener that was just installed; {@link #clearListener(Object)} only clears when the
 * caller still owns it.
 */
public final class AppLog {

    private static final String TAG = "WirelessTNTN";
    private static final int MAX_ENTRIES = 500;

    private static final ArrayDeque<String> buffer = new ArrayDeque<>();
    private static final Object lock = new Object();

    private static Object listenerOwner;
    private static OnLogListener listener;

    /**
     * rdd §5: sensitive payloads must be maskable in logs.  Debug builds default to full
     * payloads (that is the point of the tool); release builds mask by default.  The UI
     * exposes a toggle either way.
     */
    private static volatile boolean maskPayloads = !BuildConfig.DEBUG;

    public interface OnLogListener {
        void onLog(String message);
    }

    private AppLog() {}

    public static void setListener(Object owner, OnLogListener l) {
        synchronized (lock) {
            listenerOwner = owner;
            listener = l;
        }
    }

    /** Clears the listener only if {@code owner} is still the registered one. */
    public static void clearListener(Object owner) {
        synchronized (lock) {
            if (listenerOwner == owner) {
                listenerOwner = null;
                listener = null;
            }
        }
    }

    public static void setMaskPayloads(boolean mask) {
        maskPayloads = mask;
    }

    public static boolean isMaskPayloads() {
        return maskPayloads;
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
        append(msg);
    }

    public static void d(String msg) {
        Log.d(TAG, msg);
        append(msg);
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
        append(msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        append(msg + ": " + t);
    }

    /** Logs a command APDU, keeping the 4-byte header visible and masking the data field. */
    public static void command(String prefix, byte[] apdu) {
        d(prefix + " " + maskedCommand(apdu));
    }

    /** Logs a response APDU, keeping the trailing status word visible. */
    public static void response(String prefix, byte[] apdu) {
        d(prefix + " " + maskedResponse(apdu));
    }

    static String maskedCommand(byte[] apdu) {
        if (apdu == null) return "null";
        if (!maskPayloads || apdu.length <= 4) return hex(apdu);
        return hex(apdu, 0, 4) + " <" + (apdu.length - 4) + "B masked>";
    }

    static String maskedResponse(byte[] apdu) {
        if (apdu == null) return "null";
        if (!maskPayloads || apdu.length <= 2) return hex(apdu);
        return "<" + (apdu.length - 2) + "B masked> " + hex(apdu, apdu.length - 2, 2);
    }

    public static String hex(byte[] bytes) {
        if (bytes == null) return "null";
        return hex(bytes, 0, bytes.length);
    }

    public static String hex(byte[] bytes, int offset, int length) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = offset; i < offset + length && i < bytes.length; i++) {
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }

    private static void append(String msg) {
        OnLogListener l;
        synchronized (lock) {
            while (buffer.size() >= MAX_ENTRIES) {
                buffer.pollFirst();
            }
            buffer.addLast(msg);
            l = listener;
        }
        // Deliver outside the lock: the listener hops to the UI thread and must not be able
        // to stall other threads that are logging.
        if (l != null) {
            l.onLog(msg);
        }
    }

    public static List<String> getEntries() {
        synchronized (lock) {
            return new ArrayList<>(buffer);
        }
    }

    public static void clear() {
        synchronized (lock) {
            buffer.clear();
        }
    }
}
