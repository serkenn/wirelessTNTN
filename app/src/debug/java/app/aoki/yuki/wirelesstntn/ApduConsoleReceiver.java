package app.aoki.yuki.wirelesstntn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.se.omapi.Reader;
import android.se.omapi.SEService;
import android.se.omapi.Session;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Debug-only local APDU console: runs APDUs straight against a Secure Element over OMAPI.
 *
 * The NFC passthrough is the product; this is the developer interface next to it (rdd 3, rdd 8).
 * Going through a reader means every exchange is at the mercy of AID routing, observe mode and
 * whichever technology the reader happens to latch onto — none of which has anything to do with
 * the SE. For "what is actually on this card", talking to OMAPI locally removes all of it.
 *
 * The APDU state machine is {@link ApduPassthroughController}, the same one the passthrough uses,
 * so what this prints is what a reader would have seen.
 *
 * <pre>
 * adb shell am broadcast -a app.aoki.yuki.wirelesstntn.APDU_CONSOLE \
 *   --es reader SIM1 \
 *   --es apdus 00A4040008A000000151000000,80CA00E000
 * </pre>
 *
 * Responses are written to {@link AppLog}, so they land in logcat under the WirelessTNTN tag and
 * in the app's on-screen log. Payload masking is honoured; turn it off to see full responses.
 */
public class ApduConsoleReceiver extends BroadcastReceiver {

    private static final String EXTRA_READER = "reader";
    private static final String EXTRA_APDUS = "apdus";
    private static final long CONNECT_TIMEOUT_MS = 5_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String readerName = intent.getStringExtra(EXTRA_READER);
        List<byte[]> apdus = parseApdus(intent.getStringExtra(EXTRA_APDUS));

        if (readerName == null || apdus.isEmpty()) {
            AppLog.e("ApduConsole: usage: --es reader <name> --es apdus <hex>[,<hex>…]");
            return;
        }

        // OMAPI is blocking binder I/O and onReceive() runs on the main thread.
        final PendingResult pending = goAsync();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<SEService> created = new CompletableFuture<>();
        try {
            SEService service = new SEService(context.getApplicationContext(), executor, () -> {
                try {
                    run(created.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS), readerName, apdus);
                } catch (Throwable t) {
                    AppLog.e("ApduConsole: failed", t);
                } finally {
                    SEService connected = created.getNow(null);
                    if (connected != null && connected.isConnected()) {
                        connected.shutdown();
                    }
                    executor.shutdown();
                    pending.finish();
                }
            });
            created.complete(service);
        } catch (Throwable t) {
            AppLog.e("ApduConsole: could not connect to SEService", t);
            executor.shutdown();
            pending.finish();
        }
    }

    private void run(SEService service, String readerName, List<byte[]> apdus) {
        Reader target = null;
        for (Reader reader : service.getReaders()) {
            if (reader.getName().equalsIgnoreCase(readerName)) {
                target = reader;
                break;
            }
        }
        if (target == null) {
            List<String> names = new ArrayList<>();
            for (Reader reader : service.getReaders()) {
                names.add(reader.getName());
            }
            AppLog.e("ApduConsole: no such reader: " + readerName + ", available: " + names);
            return;
        }

        Session session;
        try {
            session = target.openSession();
        } catch (Throwable t) {
            AppLog.e("ApduConsole: could not open a session on " + readerName, t);
            return;
        }

        ApduPassthroughController controller = new ApduPassthroughController(session);
        try {
            AppLog.i("ApduConsole: " + apdus.size() + " APDU(s) on " + readerName);
            for (byte[] apdu : apdus) {
                AppLog.command("ApduConsole: ->", apdu);
                byte[] response = controller.process(apdu);
                AppLog.response("ApduConsole: <-", response);
                AppLog.i("ApduConsole: SW=" + statusWord(response));
            }
        } finally {
            controller.close();
        }
    }

    /** Trailing status word, or a marker when the response is too short to have one. */
    private static String statusWord(@Nullable byte[] response) {
        if (response == null || response.length < 2) {
            return "<none>";
        }
        return AppLog.hex(response, response.length - 2, 2);
    }

    private static List<byte[]> parseApdus(@Nullable String input) {
        List<byte[]> apdus = new ArrayList<>();
        if (input == null) {
            return apdus;
        }
        for (String token : input.split("[\\s,;]+")) {
            if (token.isEmpty()) {
                continue;
            }
            byte[] apdu = hexToBytes(token);
            if (apdu == null || apdu.length < 4) {
                AppLog.e("ApduConsole: skipping malformed APDU: " + token);
                continue;
            }
            apdus.add(apdu);
        }
        return apdus;
    }

    @Nullable
    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            return null;
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }
}
