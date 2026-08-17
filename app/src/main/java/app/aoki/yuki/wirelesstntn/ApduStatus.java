package app.aoki.yuki.wirelesstntn;

import java.util.NoSuchElementException;

/**
 * ISO 7816-4 status words used when the passthrough itself has to answer the reader,
 * plus the mapping from OMAPI failures onto them.
 *
 * Every failure path must produce a status word: {@link android.nfc.cardemulation.HostApduService}
 * gives us no way to say "no answer", and a reader that gets nothing simply blocks until its
 * own FWT expires.
 */
final class ApduStatus {

    /** 9000 — normal processing. */
    static final byte[] SW_OK = {(byte) 0x90, 0x00};
    /** 6700 — wrong length (APDU too short to be a valid command). */
    static final byte[] SW_WRONG_LENGTH = {0x67, 0x00};
    /** 6982 — security status not satisfied (OMAPI access rules / ARA-M refused the AID). */
    static final byte[] SW_SECURITY_NOT_SATISFIED = {0x69, (byte) 0x82};
    /** 6985 — conditions of use not satisfied (no logical channel open yet). */
    static final byte[] SW_CONDITIONS_NOT_SATISFIED = {0x69, (byte) 0x85};
    /** 6A82 — file or application not found (AID not present on the SE). */
    static final byte[] SW_FILE_NOT_FOUND = {0x6A, (byte) 0x82};
    /** 6A86 — incorrect P1/P2. */
    static final byte[] SW_INCORRECT_P1P2 = {0x6A, (byte) 0x86};
    /** 6D00 — instruction not supported (e.g. MANAGE CHANNEL, which OMAPI owns). */
    static final byte[] SW_INS_NOT_SUPPORTED = {0x6D, 0x00};
    /** 6F00 — no precise diagnosis (internal error, SE timeout). */
    static final byte[] SW_INTERNAL_ERROR = {0x6F, 0x00};

    private ApduStatus() {}

    /**
     * Maps an OMAPI failure onto a status word.
     *
     * {@code Session.openLogicalChannel()} and {@code Channel.transmit()} throw a mix of checked
     * and unchecked exceptions ({@link NoSuchElementException}, {@link SecurityException},
     * {@link IllegalStateException}, {@link IllegalArgumentException},
     * {@link UnsupportedOperationException}, {@code IOException}).  Letting any of them escape
     * onto the APDU executor kills the worker thread, which on Android takes the whole process
     * down via the default uncaught-exception handler — and the reader never sees a response.
     */
    static byte[] fromThrowable(Throwable t) {
        if (t instanceof NoSuchElementException) {
            // AID is not on the SE, or a channel is already open to a non-multiselectable applet.
            return SW_FILE_NOT_FOUND;
        }
        if (t instanceof SecurityException) {
            // Access rules (ARA-M / ARF) denied this AID or this APDU.
            return SW_SECURITY_NOT_SATISFIED;
        }
        if (t instanceof IllegalArgumentException) {
            // OMAPI rejects MANAGE CHANNEL and SELECT-by-DF-name on transmit(), and rejects
            // APDUs shorter than 4 bytes.
            return SW_INS_NOT_SUPPORTED;
        }
        if (t instanceof IllegalStateException) {
            // Session or channel was closed underneath us.
            return SW_CONDITIONS_NOT_SATISFIED;
        }
        if (t instanceof UnsupportedOperationException) {
            return SW_INS_NOT_SUPPORTED;
        }
        return SW_INTERNAL_ERROR;
    }
}
