package app.aoki.yuki.wirelesstntn;

import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.HostApduService;
import android.nfc.cardemulation.PollingFrame;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.se.omapi.Reader;
import android.se.omapi.SEService;
import android.se.omapi.Session;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NFC Host Card Emulation service that acts as a passthrough to a Secure Element via OMAPI.
 *
 * Observe mode lifecycle (mirrors AOSP HostEmulationManager):
 *   1. Start → request observe mode via NfcAdapter.setObserveModeEnabled(true)
 *   2. Reader polling frames arrive via processPollingFrames()
 *   3. Once the OMAPI session is ready → setObserveModeEnabled(false), once per field session
 *   4. APDUs arrive via processCommandApdu() → forwarded through OMAPI
 *   5. Link loss → onDeactivated(DEACTIVATION_LINK_LOSS) → re-enable observe mode (delayed)
 *   6. Repeat from 2
 *
 * This service only operates while the user has pressed Start *and* the activity is in the
 * foreground, because it holds the preferred-foreground-service registration.
 */
public class PassthroughHceService extends HostApduService {

    static final String ACTION_START = "app.aoki.yuki.wirelesstntn.START_PASSTHROUGH";
    static final String ACTION_STOP = "app.aoki.yuki.wirelesstntn.STOP_PASSTHROUGH";
    /**
     * Sent by the activity once it has (re)acquired the preferred-service registration, which is
     * the precondition NfcService checks before honouring setObserveModeEnabled().
     */
    static final String ACTION_REASSERT_OBSERVE_MODE =
            "app.aoki.yuki.wirelesstntn.REASSERT_OBSERVE_MODE";
    static final String EXTRA_READER = "reader_name";

    /**
     * rdd §7 budgets at most 5 s for a response.  Past that we answer the reader ourselves:
     * OMAPI transmit() is a blocking binder call we cannot interrupt, and a reader that never
     * gets a response just sits there until its own frame waiting time expires.
     */
    private static final long SE_RESPONSE_TIMEOUT_MS = 5_000L;

    /**
     * Same value as AOSP HostEmulationManager.RE_ENABLE_OBSERVE_MODE_DELAY_MS.
     * NfcService.setObserveMode() returns false and does nothing while an HCE transaction is
     * still considered active, so re-enabling immediately after deactivation is dropped on the
     * floor — which would leave the SE answering every reader that comes along.
     */
    private static final long OBSERVE_MODE_REENABLE_DELAY_MS = 2_000L;

    /**
     * Retry pacing for an observe mode request.
     *
     * A bounded 250 ms × 20 budget was far too short on a real device.  NfcService refuses the
     * call for two very different reasons — the caller is not the preferred service, or an HCE
     * transaction is in flight — and the second one lasts as long as the phone sits on the
     * reader, which is a perfectly normal thing for the user to do.  So the request is retried
     * with capped backoff for as long as the session lives, and only *reported* once it has been
     * outstanding long enough to be worth mentioning.  One binder call every two seconds during
     * an active session is cheap; silently leaving observe mode off is not.
     */
    private static final long OBSERVE_MODE_RETRY_INITIAL_MS = 250L;
    private static final long OBSERVE_MODE_RETRY_MAX_MS = 2_000L;
    private static final long OBSERVE_MODE_WARN_AFTER_MS = 15_000L;

    /**
     * Safety net for the case where we leave observe mode for a reader that then never starts a
     * transaction: polling frames stop arriving once observe mode is off and no onDeactivated()
     * follows either, so without this the SE would stay exposed to any reader indefinitely.
     * Refreshed on every APDU so a genuinely long transaction is never cut short.
     */
    private static final long OBSERVE_MODE_SAFETY_NET_MS = 10_000L;

    private static final long SE_CONNECT_TIMEOUT_MS = 5_000L;

    // HostApduService.onBind() is final, so the activity cannot reach the service through a
    // ServiceConnection; these statics are how the UI observes it instead.
    private static volatile PassthroughHceService instance;

    /** True while a passthrough session is running; the activity uses this as its source of truth. */
    public static boolean isPassthroughActive() {
        PassthroughHceService service = instance;
        return service != null && service.active;
    }

    @Nullable
    public static String activeReaderName() {
        PassthroughHceService service = instance;
        return service != null && service.active ? service.readerName : null;
    }

    /** Lets the UI follow session state that changes without the user asking (start failures). */
    public interface StateListener {
        void onPassthroughStateChanged(boolean active);
    }

    private static volatile Object stateListenerOwner;
    private static volatile StateListener stateListener;

    public static void setStateListener(Object owner, StateListener listener) {
        stateListenerOwner = owner;
        stateListener = listener;
    }

    /** Clears the listener only if {@code owner} is still the registered one. */
    public static void clearStateListener(Object owner) {
        if (stateListenerOwner == owner) {
            stateListenerOwner = null;
            stateListener = null;
        }
    }

    private void notifyStateChanged() {
        StateListener listener = stateListener;
        if (listener != null) {
            boolean current = active;
            mainHandler.post(() -> listener.onPassthroughStateChanged(current));
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean active;
    private volatile String readerName;

    // OMAPI objects — created on start, torn down on stop.  Volatile because they are written
    // from the SEService callback executor and read from the NFC main thread and the APDU thread.
    private volatile SEService seService;
    private volatile ApduPassthroughController passthroughController;
    private volatile ExecutorService seExecutor;
    /** Single-thread executor for OMAPI I/O; null while no session is running. */
    private volatile ExecutorService apduExecutor;

    private PollingFrameObserver pollingFrameObserver;

    // Main-thread state only.
    private Runnable pendingObserveModeTask;
    private boolean transactionAllowed;
    private boolean warnedSessionNotReady;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        pollingFrameObserver = new PollingFrameObserver(new PollingFrameObserver.Callback() {
            @Override
            public void onReaderDetected() {
                onIsoDepReaderDetected();
            }

            @Override
            public void onFieldOn() {
                AppLog.d("PassthroughHceService: field on");
            }

            @Override
            public void onFieldOff() {
                onReaderFieldLost();
            }
        });
        AppLog.i("PassthroughHceService: created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_START.equals(intent.getAction())) {
                startPassthrough(intent.getStringExtra(EXTRA_READER));
            } else if (ACTION_STOP.equals(intent.getAction())) {
                stopPassthrough();
            } else if (ACTION_REASSERT_OBSERVE_MODE.equals(intent.getAction())) {
                reassertObserveMode();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        stopPassthrough();
        AppLog.i("PassthroughHceService: destroyed");
        super.onDestroy();
    }

    // -------------------------------------------------------------------------------------
    // Session lifecycle
    // -------------------------------------------------------------------------------------

    @MainThread
    private void startPassthrough(@Nullable String requestedReader) {
        if (requestedReader == null) {
            AppLog.e("PassthroughHceService: start without a reader name, ignoring");
            return;
        }
        if (active) {
            AppLog.i("PassthroughHceService: already running on " + readerName + ", ignoring start");
            return;
        }
        active = true;
        readerName = requestedReader;
        transactionAllowed = false;
        warnedSessionNotReady = false;
        apduExecutor = Executors.newSingleThreadExecutor();

        // Observe mode is also switched on by the NFC stack because the manifest declares
        // shouldDefaultToObserveMode, but asking explicitly makes the intent (and any failure)
        // visible in the log.
        requestObserveMode(true, 0L);

        AppLog.i("PassthroughHceService: connecting OMAPI, reader=" + requestedReader);
        connectOmapi(requestedReader);
        notifyStateChanged();
    }

    private void connectOmapi(String requestedReader) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        seExecutor = executor;

        // The connected callback can fire before the SEService constructor returns, so it must
        // not read a field that is only assigned afterwards.  The future hands the instance over
        // without that race.
        CompletableFuture<SEService> created = new CompletableFuture<>();
        SEService service = new SEService(getApplicationContext(), executor, () -> {
            try {
                onSeServiceConnected(created.get(SE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                        requestedReader);
            } catch (Exception e) {
                AppLog.e("PassthroughHceService: SEService connect handling failed", e);
                failStart();
            }
        });
        seService = service;
        created.complete(service);
    }

    private void onSeServiceConnected(SEService service, String requestedReader) {
        if (!active) {
            AppLog.d("PassthroughHceService: SEService connected after stop, discarding");
            return;
        }
        Reader target = null;
        for (Reader reader : service.getReaders()) {
            if (reader.getName().equalsIgnoreCase(requestedReader)) {
                target = reader;
                break;
            }
        }
        if (target == null) {
            AppLog.e("PassthroughHceService: reader not found: " + requestedReader);
            failStart();
            return;
        }
        if (!target.isSecureElementPresent()) {
            AppLog.e("PassthroughHceService: no Secure Element present in " + requestedReader);
            failStart();
            return;
        }
        try {
            Session session = target.openSession();
            passthroughController = new ApduPassthroughController(session);
            AppLog.i("PassthroughHceService: OMAPI session ready on " + requestedReader);
        } catch (Exception e) {
            AppLog.e("PassthroughHceService: failed to open session on " + requestedReader, e);
            failStart();
        }
    }

    /** Aborts a session that could not be brought up, from whichever thread noticed. */
    private void failStart() {
        mainHandler.post(this::stopPassthrough);
    }

    @MainThread
    private void stopPassthrough() {
        cancelPendingObserveMode();
        mainHandler.removeCallbacks(observeModeSafetyNet);
        transactionAllowed = false;
        if (!active && seService == null && passthroughController == null) {
            return;
        }
        active = false;
        readerName = null;

        ExecutorService executor = apduExecutor;
        apduExecutor = null;
        if (executor != null) {
            executor.shutdown();
        }
        tearDownOmapi();

        // Observe mode is deliberately not touched here.  By the time we stop, the activity has
        // already given up the preferred-service registration, and NfcService.setObserveMode()
        // rejects callers that are not the preferred service ("Caller not preferred NFC
        // service").  The NFC stack recomputes observe mode itself when the preferred service
        // changes (CardEmulationManager.updateForShouldDefaultToObserveMode).
        AppLog.i("PassthroughHceService: stopped");
        notifyStateChanged();
    }

    private void tearDownOmapi() {
        ApduPassthroughController controller = passthroughController;
        passthroughController = null;
        if (controller != null) {
            controller.close();
        }

        SEService service = seService;
        seService = null;
        if (service != null && service.isConnected()) {
            service.shutdown();
        }

        ExecutorService executor = seExecutor;
        seExecutor = null;
        if (executor != null) {
            executor.shutdown();
        }
    }

    // -------------------------------------------------------------------------------------
    // Observe mode
    // -------------------------------------------------------------------------------------

    /**
     * Asks the NFC stack to toggle observe mode, retrying while it refuses.
     *
     * {@code NfcAdapter.setObserveModeEnabled()} returns false — without throwing — when the
     * caller is not the preferred service or when an HCE transaction is still in flight, so a
     * single fire-and-forget call is not enough to rely on.
     */
    private void requestObserveMode(boolean enable, long delayMs) {
        mainHandler.post(() -> {
            cancelPendingObserveMode();
            ObserveModeTask task = new ObserveModeTask(enable);
            pendingObserveModeTask = task;
            mainHandler.postDelayed(task, delayMs);
        });
    }

    /**
     * Re-requests observe mode after the activity regained the preferred-service registration.
     *
     * Without this, a request that ran out of retries while the app was not preferred would never
     * be picked up again, because nothing else in the flow asks for observe mode until the next
     * link loss.
     */
    @MainThread
    private void reassertObserveMode() {
        if (!active) {
            return;
        }
        if (transactionAllowed) {
            // Deliberately out of observe mode for a transaction in progress; leave it alone.
            return;
        }
        AppLog.d("PassthroughHceService: re-asserting observe mode");
        requestObserveMode(true, 0L);
    }

    @MainThread
    private void cancelPendingObserveMode() {
        if (pendingObserveModeTask != null) {
            mainHandler.removeCallbacks(pendingObserveModeTask);
            pendingObserveModeTask = null;
        }
    }

    private final class ObserveModeTask implements Runnable {
        private final boolean enable;
        private int attempt;
        private long startedAt;
        private long delay = OBSERVE_MODE_RETRY_INITIAL_MS;
        private boolean warned;

        ObserveModeTask(boolean enable) {
            this.enable = enable;
        }

        @Override
        public void run() {
            if (pendingObserveModeTask != this) {
                return; // superseded
            }
            if (!active) {
                // No session, nothing to protect.
                pendingObserveModeTask = null;
                return;
            }
            if (startedAt == 0L) {
                startedAt = SystemClock.uptimeMillis();
            }
            attempt++;

            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(PassthroughHceService.this);
            if (adapter == null) {
                AppLog.e("PassthroughHceService: no NFC adapter, cannot set observe mode");
                pendingObserveModeTask = null;
                return;
            }
            if (adapter.isObserveModeEnabled() == enable) {
                // Someone else already put us in the state we wanted (the NFC stack does this
                // on preferred-service changes for shouldDefaultToObserveMode services).
                finish("already");
                return;
            }
            if (adapter.setObserveModeEnabled(enable)) {
                finish("attempt " + attempt);
                return;
            }

            long outstanding = SystemClock.uptimeMillis() - startedAt;
            if (!warned && outstanding >= OBSERVE_MODE_WARN_AFTER_MS) {
                warned = true;
                // Three causes seen in practice, in increasing order of annoyance:
                //  - an HCE transaction is still in flight (benign, resolves itself)
                //  - this app is no longer the preferred foreground service
                //  - the controller HAL desynced and rejects the change outright.  On a Pixel 9a
                //    this shows up as "StNfcHal: mObserverMode got out of sync" followed by
                //    "nfcManager_setObserveMode: ... result 0, observe mode is now disabled",
                //    and only survives an NFC off/on cycle.
                AppLog.e("PassthroughHceService: setObserveModeEnabled(" + enable + ") refused for "
                        + (outstanding / 1000) + "s (" + attempt + " attempts). Still retrying. "
                        + "Causes: a transaction still in flight, this app no longer being the "
                        + "preferred foreground NFC service, or the NFC controller HAL being out "
                        + "of sync (check logcat for StNfcHal/libnfc_nci; toggling NFC off and on "
                        + "reinitialises it).");
            }
            delay = Math.min(delay * 2, OBSERVE_MODE_RETRY_MAX_MS);
            mainHandler.postDelayed(this, delay);
        }

        private void finish(String how) {
            AppLog.i("PassthroughHceService: observe mode " + (enable ? "enabled" : "disabled")
                    + " (" + how + ")");
            pendingObserveModeTask = null;
        }
    }

    // -------------------------------------------------------------------------------------
    // NFC callbacks
    // -------------------------------------------------------------------------------------

    @Override
    public void processPollingFrames(List<PollingFrame> frames) {
        pollingFrameObserver.onPollingFrames(frames);
    }

    @MainThread
    private void onIsoDepReaderDetected() {
        if (!active || transactionAllowed) {
            return; // polling frames arrive continuously; act on the first one only
        }
        if (passthroughController == null) {
            if (!warnedSessionNotReady) {
                warnedSessionNotReady = true;
                AppLog.e("PassthroughHceService: reader detected but the OMAPI session is not "
                        + "ready yet, staying in observe mode");
            }
            return;
        }
        transactionAllowed = true;
        AppLog.i("PassthroughHceService: reader detected, leaving observe mode");
        requestObserveMode(false, 0L);
        refreshObserveModeSafetyNet();
    }

    @MainThread
    private void onReaderFieldLost() {
        // Still in observe mode here (polling frames only arrive while it is on), so there is
        // nothing to undo — just re-arm the "session not ready" warning for the next approach.
        warnedSessionNotReady = false;
    }

    @MainThread
    private void refreshObserveModeSafetyNet() {
        mainHandler.removeCallbacks(observeModeSafetyNet);
        mainHandler.postDelayed(observeModeSafetyNet, OBSERVE_MODE_SAFETY_NET_MS);
    }

    private final Runnable observeModeSafetyNet = new Runnable() {
        @Override
        public void run() {
            if (!active || !transactionAllowed) {
                return;
            }
            AppLog.e("PassthroughHceService: no transaction within " + OBSERVE_MODE_SAFETY_NET_MS
                    + "ms of leaving observe mode, returning to observe mode");
            transactionAllowed = false;
            warnedSessionNotReady = false;
            requestObserveMode(true, 0L);
        }
    };

    /**
     * Returns null to signal asynchronous processing; the response is sent via
     * sendResponseApdu() once the SE answers, or by the timeout watchdog.
     */
    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (apdu == null || apdu.length < 4) {
            AppLog.e("PassthroughHceService: malformed APDU from reader");
            return ApduStatus.SW_WRONG_LENGTH;
        }
        AppLog.command("PassthroughHceService: APDU ->", apdu);
        refreshObserveModeSafetyNet();

        final ApduPassthroughController controller = passthroughController;
        final ExecutorService executor = apduExecutor;
        if (controller == null || executor == null) {
            AppLog.e("PassthroughHceService: APDU received but no passthrough session is running");
            return ApduStatus.SW_CONDITIONS_NOT_SATISFIED;
        }

        final byte[] command = Arrays.copyOf(apdu, apdu.length);
        final AtomicBoolean answered = new AtomicBoolean(false);
        final Runnable watchdog = () -> {
            if (answered.compareAndSet(false, true)) {
                AppLog.e("PassthroughHceService: SE did not answer within "
                        + SE_RESPONSE_TIMEOUT_MS + "ms, failing the APDU");
                sendResponseApdu(ApduStatus.SW_INTERNAL_ERROR);
            }
        };
        mainHandler.postDelayed(watchdog, SE_RESPONSE_TIMEOUT_MS);

        try {
            executor.execute(() -> {
                byte[] response;
                try {
                    response = controller.process(command);
                } catch (Throwable t) {
                    // process() is not supposed to throw; this is the last line of defence
                    // before an uncaught exception takes the whole process down.
                    AppLog.e("PassthroughHceService: unexpected passthrough failure", t);
                    response = ApduStatus.fromThrowable(t);
                }
                mainHandler.removeCallbacks(watchdog);
                if (answered.compareAndSet(false, true)) {
                    AppLog.response("PassthroughHceService: APDU <-", response);
                    sendResponseApdu(response);
                }
            });
        } catch (RejectedExecutionException e) {
            mainHandler.removeCallbacks(watchdog);
            answered.set(true);
            AppLog.e("PassthroughHceService: session was torn down while handling an APDU");
            return ApduStatus.SW_CONDITIONS_NOT_SATISFIED;
        }
        return null; // async — the response goes out via sendResponseApdu()
    }

    @Override
    public void onDeactivated(int reason) {
        AppLog.i("PassthroughHceService: deactivated reason="
                + (reason == DEACTIVATION_LINK_LOSS ? "LINK_LOSS" : "DESELECTED"));

        if (reason == DEACTIVATION_DESELECTED) {
            // The reader is still in the field and will select another application; keeping the
            // channel and staying out of observe mode is what lets a PPSE → SELECT AID sequence
            // work. The channel is replaced by the next SELECT anyway.
            return;
        }

        // Link loss: the card left the field.  Drop the channel and re-arm observe mode.
        mainHandler.removeCallbacks(observeModeSafetyNet);
        ApduPassthroughController controller = passthroughController;
        if (controller != null) {
            controller.closeCurrentChannel();
        }
        transactionAllowed = false;
        warnedSessionNotReady = false;
        if (active) {
            requestObserveMode(true, OBSERVE_MODE_REENABLE_DELAY_MS);
        }
    }
}
