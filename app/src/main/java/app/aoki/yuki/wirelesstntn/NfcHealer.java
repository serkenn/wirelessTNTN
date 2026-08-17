package app.aoki.yuki.wirelesstntn;

import androidx.annotation.MainThread;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Restarts the NFC stack through root, to recover a desynced controller HAL.
 *
 * Observe mode is the whole safety story of this app, and on some devices the controller drops out
 * of sync with the framework and refuses every {@code setObserveModeEnabled()} call — on a Pixel 9a
 * that is "StNfcHal: mObserverMode got out of sync" followed by "nfcManager_setObserveMode: …
 * result 0, observe mode is now disabled".  No amount of retrying fixes it; reinitialising the HAL
 * does.  A plain app cannot do that ({@code NfcAdapter.enable()} is a system API), but rdd-qa Q17
 * already assumes a rooted developer device, so shelling out is available when the user opts in.
 *
 * Nothing here runs unless the user presses the button.
 */
final class NfcHealer {

    private static final long TIMEOUT_SECONDS = 30L;

    interface Callback {
        void onFinished(boolean success, String message);
    }

    private NfcHealer() {}

    /**
     * Turns NFC off and back on. Runs on a worker thread; {@code callback} fires on that thread,
     * so hop back to the UI yourself.
     */
    @MainThread
    static void restartNfc(Callback callback) {
        new Thread(() -> {
            try {
                // `svc nfc` goes through the framework, so this is the same thing as toggling the
                // system setting, not a poke at the driver. Absolute paths because the shell su
                // hands us does not necessarily have /system/bin on PATH, and a missing binary
                // there looks exactly like "root was refused" (both surface as exit 127).
                Process process = new ProcessBuilder("su", "-c",
                        "/system/bin/svc nfc disable; /system/bin/sleep 3; /system/bin/svc nfc enable")
                        .redirectErrorStream(true)
                        .start();
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    callback.onFinished(false, "timed out after " + TIMEOUT_SECONDS + "s");
                    return;
                }
                String output = readAll(process);
                int exit = process.exitValue();
                if (exit == 0) {
                    callback.onFinished(true, "NFC restarted");
                } else {
                    callback.onFinished(false, "su exited with " + exit
                            + (output.isEmpty() ? " and said nothing — is root granted to this app?"
                                                : ": " + output));
                }
            } catch (IOException e) {
                callback.onFinished(false, "could not run su: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                callback.onFinished(false, "interrupted");
            }
        }, "NfcHealer").start();
    }

    /** Whatever su printed, trimmed to something that fits in a log line and a toast. */
    private static String readAll(Process process) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && sb.length() < 300) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                sb.append(line.trim());
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }
}
