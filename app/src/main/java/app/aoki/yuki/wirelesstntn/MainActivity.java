package app.aoki.yuki.wirelesstntn;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.se.omapi.Reader;
import android.se.omapi.SEService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Main activity: SE reader selector, AID editor, Start/Stop toggle, log viewer.
 *
 * Foreground-only operation (rdd §8):
 *   - setPreferredService() is what makes the HCE service receive polling frames and lets it
 *     toggle observe mode; its return value is checked, because a refusal is otherwise
 *     completely silent and the app just never sees a reader.
 *   - onPause() gives the registration back and, unless this is a configuration change, stops
 *     the passthrough outright so no OMAPI session survives in the background.
 *
 * The service — not this activity — owns the session state, so a recreated activity picks up
 * whatever is actually running instead of resetting its buttons.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "wirelesstntn";
    private static final String KEY_MASK_PAYLOADS = "mask_payloads";
    private static final long SE_CONNECT_TIMEOUT_MS = 5_000L;

    private Spinner seSpinner;
    private Button startStopButton;
    private EditText aidEditor;
    private Button applyAidsButton;
    private Button defaultAidsButton;
    private Button paymentAidsButton;
    private CheckBox maskPayloadsCheckBox;
    private TextView logTextView;
    private ScrollView logScrollView;

    @Nullable
    private NfcAdapter nfcAdapter;
    @Nullable
    private CardEmulation cardEmulation;
    private ComponentName serviceComponent;
    private SharedPreferences prefs;

    /** Optimistic UI state; reconciled against the service on resume and on state callbacks. */
    private boolean sessionRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        seSpinner = findViewById(R.id.se_spinner);
        startStopButton = findViewById(R.id.start_stop_button);
        aidEditor = findViewById(R.id.aid_editor);
        applyAidsButton = findViewById(R.id.apply_aids_button);
        defaultAidsButton = findViewById(R.id.default_aids_button);
        paymentAidsButton = findViewById(R.id.payment_aids_button);
        maskPayloadsCheckBox = findViewById(R.id.mask_payloads_checkbox);
        logTextView = findViewById(R.id.log_text_view);
        logScrollView = findViewById(R.id.log_scroll_view);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        serviceComponent = new ComponentName(this, PassthroughHceService.class);

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            AppLog.e("This device has no NFC adapter; the passthrough cannot run");
        } else {
            cardEmulation = CardEmulation.getInstance(nfcAdapter);
        }

        boolean mask = prefs.getBoolean(KEY_MASK_PAYLOADS, AppLog.isMaskPayloads());
        AppLog.setMaskPayloads(mask);
        maskPayloadsCheckBox.setChecked(mask);
        maskPayloadsCheckBox.setOnCheckedChangeListener((v, checked) -> {
            AppLog.setMaskPayloads(checked);
            prefs.edit().putBoolean(KEY_MASK_PAYLOADS, checked).apply();
            AppLog.i("Payload masking " + (checked ? "on" : "off"));
        });

        startStopButton.setOnClickListener(v -> {
            if (sessionRequested || PassthroughHceService.isPassthroughActive()) {
                stopSession();
            } else {
                startSession();
            }
        });
        applyAidsButton.setOnClickListener(v -> applyAids(aidEditor.getText().toString()));
        defaultAidsButton.setOnClickListener(
                v -> aidEditor.setText(AidRegistry.format(AidRegistry.DEFAULT_AIDS)));
        paymentAidsButton.setOnClickListener(v -> appendPaymentAids());

        // Replay what has already been logged: the buffer outlives this activity instance.
        for (String entry : AppLog.getEntries()) {
            logTextView.append(entry + "\n");
        }
        AppLog.setListener(this, msg -> runOnUiThread(() -> appendLog(msg)));
        PassthroughHceService.setStateListener(this, active -> runOnUiThread(() -> {
            sessionRequested = active;
            updateUi();
        }));
    }

    @Override
    protected void onResume() {
        super.onResume();

        // The service is the source of truth: a rotation or a failed start must not leave the
        // button lying about what is running.
        sessionRequested = PassthroughHceService.isPassthroughActive();
        updateUi();
        showEffectiveAids();

        if (sessionRequested) {
            // A session survived this activity instance (rotation): show the reader it is
            // actually using rather than re-enumerating and losing the selection.
            showActiveReader(PassthroughHceService.activeReaderName());
            // Re-register so polling frames and APDUs keep coming while we are visible again,
            // then have the service re-assert observe mode.  NfcService refuses
            // setObserveModeEnabled() from anyone that is not the preferred service, so any
            // request the service made while we were away was dropped on the floor.
            if (registerAsPreferredService()) {
                sendServiceAction(PassthroughHceService.ACTION_REASSERT_OBSERVE_MODE, null);
            }
        } else {
            loadSeReaders();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (isChangingConfigurations()) {
            // Hand the registration back for the moment; onResume() takes it again and the
            // session keeps running across the recreation.
            if (cardEmulation != null) {
                cardEmulation.unsetPreferredService(this);
            }
            return;
        }

        // rdd §8: no passthrough while backgrounded — and no OMAPI session left open either.
        if (PassthroughHceService.isPassthroughActive() || sessionRequested) {
            AppLog.i("MainActivity: leaving the foreground, stopping the passthrough");
            sendServiceAction(PassthroughHceService.ACTION_STOP, null);
            sessionRequested = false;
        }
        if (cardEmulation != null && !cardEmulation.unsetPreferredService(this)) {
            AppLog.d("MainActivity: unsetPreferredService() refused (not the preferred service)");
        }
    }

    @Override
    protected void onDestroy() {
        AppLog.clearListener(this);
        PassthroughHceService.clearStateListener(this);
        super.onDestroy();
    }

    // -------------------------------------------------------------------------------------
    // Session control
    // -------------------------------------------------------------------------------------

    private void startSession() {
        if (nfcAdapter == null || cardEmulation == null) {
            fail(getString(R.string.error_no_nfc));
            return;
        }
        if (!nfcAdapter.isEnabled()) {
            fail(getString(R.string.error_nfc_disabled));
            return;
        }
        if (seSpinner.getCount() == 0) {
            fail(getString(R.string.error_no_se));
            return;
        }
        String readerName = (String) seSpinner.getSelectedItem();

        // Must succeed before anything else: without the preferred-service registration the
        // service gets no polling frames and NfcAdapter.setObserveModeEnabled() refuses us.
        if (!registerAsPreferredService()) {
            return;
        }

        AppLog.i("MainActivity: starting passthrough on " + readerName);
        sendServiceAction(PassthroughHceService.ACTION_START, readerName);
        sessionRequested = true;
        updateUi();
    }

    private void stopSession() {
        AppLog.i("MainActivity: stopping passthrough");
        // Sent before unsetPreferredService() only for readability; the service handles the
        // intent on the next main-thread message either way and never assumes it is still
        // the preferred service when it stops.
        sendServiceAction(PassthroughHceService.ACTION_STOP, null);
        if (cardEmulation != null) {
            cardEmulation.unsetPreferredService(this);
        }
        sessionRequested = false;
        updateUi();
    }

    private boolean registerAsPreferredService() {
        if (cardEmulation == null) {
            return false;
        }
        if (cardEmulation.setPreferredService(this, serviceComponent)) {
            return true;
        }
        // AOSP PreferredServices.isForegroundAllowedLocked() refuses a foreground service whose
        // category-other AIDs collide with the default payment app when the NFC setting is
        // "use default always".
        fail(getString(R.string.error_preferred_service));
        return false;
    }

    private void sendServiceAction(String action, @Nullable String readerName) {
        Intent intent = new Intent(this, PassthroughHceService.class);
        intent.setAction(action);
        if (readerName != null) {
            intent.putExtra(PassthroughHceService.EXTRA_READER, readerName);
        }
        startService(intent);
    }

    // -------------------------------------------------------------------------------------
    // Secure Element readers
    // -------------------------------------------------------------------------------------

    /**
     * Connects to SEService, enumerates the readers, then shuts the transient service down
     * again — the passthrough service opens its own connection when a session starts.
     */
    private void loadSeReaders() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        // The connected callback can run before the constructor returns, so it must not read a
        // variable that is only assigned afterwards.
        CompletableFuture<SEService> created = new CompletableFuture<>();
        try {
            SEService service = new SEService(this, executor, () -> {
                List<String> names = new ArrayList<>();
                try {
                    SEService connected =
                            created.get(SE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    for (Reader reader : connected.getReaders()) {
                        names.add(reader.getName());
                    }
                } catch (Exception e) {
                    AppLog.e("MainActivity: failed to enumerate Secure Element readers", e);
                } finally {
                    // Shut down even when getReaders() threw, so the binding never leaks.
                    SEService connected = created.getNow(null);
                    if (connected != null && connected.isConnected()) {
                        connected.shutdown();
                    }
                    executor.shutdown();
                }
                runOnUiThread(() -> onReadersLoaded(names));
            });
            created.complete(service);
        } catch (Exception e) {
            executor.shutdown();
            AppLog.e("MainActivity: failed to connect to SEService", e);
        }
    }

    /** Pins the spinner to the reader a running session is using. */
    private void showActiveReader(@Nullable String activeReader) {
        if (activeReader == null) {
            return;
        }
        Object selected = seSpinner.getSelectedItem();
        if (activeReader.equals(selected)) {
            return;
        }
        List<String> single = new ArrayList<>();
        single.add(activeReader);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, single);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        seSpinner.setAdapter(adapter);
    }

    private void onReadersLoaded(List<String> names) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (names.isEmpty()) {
            AppLog.e("No Secure Element readers found on this device");
            seSpinner.setAdapter(null);
            updateUi();
            return;
        }
        String previous = (String) seSpinner.getSelectedItem();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        seSpinner.setAdapter(adapter);
        if (previous != null) {
            int index = names.indexOf(previous);
            if (index >= 0) {
                seSpinner.setSelection(index);
            }
        }
        AppLog.i("SE readers available: " + names);
        updateUi();
    }

    // -------------------------------------------------------------------------------------
    // AID registration
    // -------------------------------------------------------------------------------------

    /**
     * Fills the editor with the AIDs currently registered at runtime.  The NFC service only
     * hands back <em>dynamic</em> groups (RegisteredServicesCache.getAidGroupForService), so a
     * null answer means nothing has been registered yet and the manifest baseline is in effect.
     */
    private void showEffectiveAids() {
        if (cardEmulation == null || aidEditor.hasFocus()) {
            return;
        }
        List<String> aids = cardEmulation.getAidsForService(serviceComponent,
                CardEmulation.CATEGORY_OTHER);
        aidEditor.setText(AidRegistry.format(aids != null ? aids : AidRegistry.DEFAULT_AIDS));
    }

    private void appendPaymentAids() {
        AidRegistry.ParseResult current = AidRegistry.parse(aidEditor.getText().toString());
        List<String> merged = new ArrayList<>(current.accepted);
        for (String aid : AidRegistry.PAYMENT_AIDS) {
            if (!merged.contains(aid)) {
                merged.add(aid);
            }
        }
        aidEditor.setText(AidRegistry.format(merged));
        AppLog.i("Payment AIDs added to the editor. Registering them can make "
                + "setPreferredService() fail when a default payment app is installed and NFC "
                + "is set to \"use default: always\".");
    }

    private void applyAids(String input) {
        if (cardEmulation == null) {
            fail(getString(R.string.error_no_nfc));
            return;
        }
        AidRegistry.ParseResult result = AidRegistry.parse(input);
        if (result.hasRejections()) {
            AppLog.e("Rejected AIDs (need 5-16 bytes of hex, optional trailing * or #): "
                    + result.rejected);
        }
        if (result.accepted.isEmpty()) {
            fail(getString(R.string.error_no_valid_aids));
            return;
        }
        // A dynamic AID group replaces the manifest's static group for this category, so the
        // list registered here has to be the complete one.
        if (cardEmulation.registerAidsForService(serviceComponent,
                CardEmulation.CATEGORY_OTHER, result.accepted)) {
            AppLog.i("Registered " + result.accepted.size() + " AIDs: " + result.accepted);
            Toast.makeText(this,
                    getString(R.string.aids_registered, result.accepted.size()),
                    Toast.LENGTH_SHORT).show();
        } else {
            fail(getString(R.string.error_aid_registration));
        }
    }

    // -------------------------------------------------------------------------------------
    // UI plumbing
    // -------------------------------------------------------------------------------------

    private void updateUi() {
        boolean running = sessionRequested || PassthroughHceService.isPassthroughActive();
        startStopButton.setText(running ? R.string.stop : R.string.start);
        startStopButton.setEnabled(running || seSpinner.getCount() > 0);
        seSpinner.setEnabled(!running);
        aidEditor.setEnabled(!running);
        applyAidsButton.setEnabled(!running);
        defaultAidsButton.setEnabled(!running);
        paymentAidsButton.setEnabled(!running);
    }

    private void fail(String message) {
        AppLog.e(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void appendLog(String msg) {
        logTextView.append(msg + "\n");
        logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
