package app.aoki.yuki.wirelesstntn;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * The set of AIDs this service claims, and the parsing/validation around it.
 *
 * rdd-qa Q9 asks for "whatever AID shows up gets forwarded to OMAPI", but Android has no
 * catch-all AID: {@code CardEmulation.isValidAid()} rejects anything shorter than five bytes,
 * so a bare {@code A0*} cannot be registered (that is what the earlier IllegalArgumentException
 * was about).  The practical answer is a small static baseline plus dynamic registration of
 * whatever the developer actually needs, which is what {@link MainActivity} drives.
 *
 * Two things worth knowing about the baseline:
 * <ul>
 *   <li>Registering a dynamic AID group for a category <em>replaces</em> the manifest's static
 *       group for that category, so the list handed to
 *       {@code CardEmulation.registerAidsForService()} must always be complete.</li>
 *   <li>Payment RIDs are deliberately <em>not</em> in the default list.  A foreground service
 *       that claims payment AIDs under category "other" is refused preferred-service
 *       registration on devices whose NFC setting is "use default always"
 *       (AOSP PreferredServices.isForegroundAllowedLocked), which silently breaks the whole
 *       app.  They are offered as an opt-in preset instead.</li>
 * </ul>
 */
public final class AidRegistry {

    /** Secure-Element development AIDs: the safe baseline, mirrored in res/xml/apduservice.xml. */
    public static final List<String> DEFAULT_AIDS = Collections.unmodifiableList(Arrays.asList(
            "A000000151*",   // GlobalPlatform Issuer Security Domain / SSD
            "A000000087*",   // 3GPP USIM / ISIM
            "A000000063*",   // PKCS#15
            "A000000647*",   // FIDO
            "D276000085*",   // NDEF Type 4 Tag
            "F000000000*",   // Proprietary
            "F000000001*",
            "F000000002*",
            "F000000003*"));

    /**
     * Payment RIDs, opt-in.  Claiming these can make setPreferredService() fail when a default
     * payment app is installed; the log says so when it happens.
     */
    public static final List<String> PAYMENT_AIDS = Collections.unmodifiableList(Arrays.asList(
            "A000000003*",   // Visa
            "A000000004*",   // Mastercard / Maestro
            "A000000025*",   // American Express
            "A000000029*",   // American Express (alternate RID)
            "A000000032*",   // Visa Electron
            "A000000045*",   // Maestro (UK Domestic)
            "A000000065*",   // JCB
            "A000000152*",   // Discover / Diners Club
            "A000000277*",   // Interac
            "A000000333*",   // China UnionPay
            "A000000632*",   // eftpos Australia
            "A000000658*",   // MIR
            "A000000724*",   // RuPay / NPCI
            "A000000006*",   // Bancontact
            "A000000042*",   // Carte Bancaire
            "A000000172*",   // Girocard
            "A000000031*",   // Visa Transit
            "A000000046*"));  // Visa Transit (alternate)

    /** Outcome of parsing a user-supplied AID list. */
    public static final class ParseResult {
        public final List<String> accepted;
        public final List<String> rejected;

        ParseResult(List<String> accepted, List<String> rejected) {
            this.accepted = Collections.unmodifiableList(accepted);
            this.rejected = Collections.unmodifiableList(rejected);
        }

        public boolean hasRejections() {
            return !rejected.isEmpty();
        }
    }

    private AidRegistry() {}

    /**
     * Same rules as {@code android.nfc.cardemulation.CardEmulation.isValidAid()}, reimplemented
     * so the validation can be unit tested without a device: 5 to 16 bytes of hex, optionally
     * followed by a single {@code *} (prefix match) or {@code #} (subset match).
     */
    public static boolean isValidAid(@Nullable String aid) {
        if (aid == null) {
            return false;
        }
        String hex = aid;
        if (hex.endsWith("*") || hex.endsWith("#")) {
            hex = hex.substring(0, hex.length() - 1);
        }
        if (hex.length() < 10 || hex.length() > 32 || hex.length() % 2 != 0) {
            return false;
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            boolean isHexDigit = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')
                    || (c >= 'a' && c <= 'f');
            if (!isHexDigit) {
                return false;
            }
        }
        return true;
    }

    /** Splits a free-form list (whitespace, comma or semicolon separated) into validated AIDs. */
    @NonNull
    public static ParseResult parse(@Nullable String input) {
        List<String> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        if (input == null) {
            return new ParseResult(accepted, rejected);
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String token : input.split("[\\s,;]+")) {
            if (token.isEmpty()) {
                continue;
            }
            String normalized = token.toUpperCase(Locale.ROOT);
            if (!isValidAid(normalized)) {
                rejected.add(token);
            } else if (seen.add(normalized)) {
                accepted.add(normalized);
            }
        }
        return new ParseResult(accepted, rejected);
    }

    /** Renders a list back into the newline-separated form shown in the UI. */
    @NonNull
    public static String format(@Nullable List<String> aids) {
        if (aids == null || aids.isEmpty()) {
            return "";
        }
        return String.join("\n", aids);
    }
}
