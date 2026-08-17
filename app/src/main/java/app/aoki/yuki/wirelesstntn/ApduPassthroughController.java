package app.aoki.yuki.wirelesstntn;

import android.se.omapi.Channel;
import android.se.omapi.Session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.IOException;
import java.util.Arrays;

/**
 * Domain logic for APDU passthrough.
 *
 * Owns a single OMAPI Session (already opened on the chosen Secure Element reader) and
 * implements the state machine:
 *   - SELECT by DF name (AID) → close any open channel, open a new logical channel for that AID
 *   - MANAGE CHANNEL          → refused; OMAPI owns logical-channel management
 *   - anything else           → forwarded through the current channel
 *
 * SELECT has to be intercepted rather than forwarded because OMAPI explicitly forbids both
 * SELECT-by-DF-name and MANAGE CHANNEL on {@link Channel#transmit(byte[])}.
 *
 * {@link #process(byte[])} never throws: every failure is mapped onto a status word by
 * {@link ApduStatus#fromThrowable(Throwable)} so the caller always has something to send back.
 */
public class ApduPassthroughController {

    private final Session session;
    private Channel currentChannel;

    public ApduPassthroughController(@NonNull Session session) {
        this.session = session;
    }

    /**
     * Processes one APDU from the NFC reader and returns the bytes to send back.
     * Never throws — errors become status words.
     */
    @NonNull
    public synchronized byte[] process(@Nullable byte[] apdu) {
        try {
            if (apdu == null || apdu.length < 4) {
                AppLog.e("Passthrough: malformed APDU (" + (apdu == null ? "null" : apdu.length)
                        + " bytes)");
                return ApduStatus.SW_WRONG_LENGTH;
            }
            if (isManageChannel(apdu)) {
                // Logical channels are opened by OMAPI on our behalf; a reader-driven MANAGE
                // CHANNEL cannot be honoured and transmit() would reject it anyway.
                AppLog.e("Passthrough: MANAGE CHANNEL refused (OMAPI owns channel management)");
                return ApduStatus.SW_INS_NOT_SUPPORTED;
            }
            if (isSelectByAid(apdu)) {
                return handleSelectByAid(apdu);
            }
            return forwardToCurrentChannel(apdu);
        } catch (Throwable t) {
            byte[] sw = ApduStatus.fromThrowable(t);
            AppLog.e("Passthrough: APDU failed, answering SW=" + AppLog.hex(sw), t);
            return sw;
        }
    }

    @NonNull
    private byte[] handleSelectByAid(@NonNull byte[] apdu) throws IOException {
        int[] data = commandDataRange(apdu);
        if (data == null) {
            AppLog.e("Passthrough: SELECT with malformed length field");
            return ApduStatus.SW_WRONG_LENGTH;
        }

        // An empty AID means "select the default applet"; OMAPI expresses that as a null AID.
        byte[] aid = data[1] == 0 ? null : Arrays.copyOfRange(apdu, data[0], data[0] + data[1]);
        byte p2 = apdu[3];
        AppLog.i("Passthrough: SELECT AID=" + (aid == null ? "<default>" : AppLog.hex(aid))
                + " P2=" + String.format("%02X", p2));

        // A fresh SELECT supersedes whatever was selected before.
        closeCurrentChannel();

        // P2 is forwarded so the reader still controls which template (FCI/FCP/FMD, or none)
        // the SE returns.
        Channel channel = session.openLogicalChannel(aid, p2);
        if (channel == null) {
            AppLog.e("Passthrough: SE provided no channel for this AID");
            return ApduStatus.SW_FILE_NOT_FOUND;
        }
        currentChannel = channel;

        byte[] selectResponse = channel.getSelectResponse();
        AppLog.response("Passthrough: SELECT <-", selectResponse);
        // getSelectResponse() includes the status word.  It is null when no SELECT was actually
        // performed (default applet), in which case the applet is selected and 9000 is correct.
        return (selectResponse != null && selectResponse.length >= 2) ? selectResponse
                : ApduStatus.SW_OK;
    }

    @NonNull
    private byte[] forwardToCurrentChannel(@NonNull byte[] apdu) throws IOException {
        Channel channel = currentChannel;
        if (channel == null || !channel.isOpen()) {
            AppLog.e("Passthrough: APDU received but no channel is open");
            return ApduStatus.SW_CONDITIONS_NOT_SATISFIED;
        }
        byte[] command = normalizeChannelNumber(apdu);
        byte[] response = channel.transmit(command);
        if (response == null || response.length < 2) {
            AppLog.e("Passthrough: SE returned a truncated response");
            return ApduStatus.SW_INTERNAL_ERROR;
        }
        return response;
    }

    /** Closes the current logical channel (e.g. after NFC link loss). */
    public synchronized void closeCurrentChannel() {
        if (currentChannel != null) {
            try {
                currentChannel.close();
            } catch (Exception e) {
                AppLog.d("Passthrough: ignoring error while closing channel: " + e);
            }
            currentChannel = null;
        }
    }

    /** Full teardown: closes the channel and the underlying session. */
    public synchronized void close() {
        closeCurrentChannel();
        try {
            session.close();
        } catch (Exception e) {
            AppLog.e("Passthrough: error closing session", e);
        }
    }

    // ---------------------------------------------------------------------------------------
    // APDU parsing helpers (package-private so they can be unit tested without an SE).
    // ---------------------------------------------------------------------------------------

    /**
     * True for SELECT by DF name (INS=A4, P1=04) on any interindustry class byte.
     *
     * The class byte is checked loosely on purpose: readers may address a logical channel
     * (CLA 00-03, 40-7F) or use secure messaging (CLA 0C-0F), and all of those still mean
     * "select an application by AID".
     */
    @VisibleForTesting
    static boolean isSelectByAid(@NonNull byte[] apdu) {
        return isInterindustry(apdu[0])
                && apdu[1] == (byte) 0xA4
                && (apdu[2] & 0xFF) == 0x04;
    }

    /** True for MANAGE CHANNEL (INS=70). */
    @VisibleForTesting
    static boolean isManageChannel(@NonNull byte[] apdu) {
        return isInterindustry(apdu[0]) && apdu[1] == 0x70;
    }

    /** ISO 7816-4: 00-0F is the first interindustry class, 40-7F the further interindustry one. */
    private static boolean isInterindustry(byte cla) {
        int c = cla & 0xFF;
        return c <= 0x0F || (c >= 0x40 && c <= 0x7F);
    }

    /**
     * Clears the logical-channel number the reader put in the class byte.
     *
     * The OMAPI implementation inserts the channel number of the channel we opened, so a
     * reader-supplied number would either be overwritten or make transmit() reject the APDU.
     * Only the first interindustry class (00-0F, channel in b2b1) is rewritten; the further
     * interindustry encoding is left alone rather than guessed at.
     */
    @VisibleForTesting
    static byte[] normalizeChannelNumber(@NonNull byte[] apdu) {
        int cla = apdu[0] & 0xFF;
        if (cla > 0x0F || (cla & 0x03) == 0) {
            return apdu;
        }
        byte[] rewritten = apdu.clone();
        rewritten[0] = (byte) (cla & 0xFC);
        AppLog.d("Passthrough: CLA " + String.format("%02X", cla) + " -> "
                + String.format("%02X", rewritten[0]) + " (channel number cleared)");
        return rewritten;
    }

    /**
     * Locates the command data field of an APDU.
     *
     * @return {@code {offset, length}}, {@code {4, 0}} when the command carries no data,
     *         or {@code null} when the length field is inconsistent with the APDU size.
     */
    @Nullable
    @VisibleForTesting
    static int[] commandDataRange(@NonNull byte[] apdu) {
        int len = apdu.length;
        if (len <= 5) {
            // Case 1 (no Lc/Le) and case 2S (Le only) carry no data.
            return new int[]{4, 0};
        }
        int lc = apdu[4] & 0xFF;
        if (lc != 0) {
            // Short form: one Lc byte, then the data, then an optional Le byte.
            return len >= 5 + lc ? new int[]{5, lc} : null;
        }
        // Extended form: 00 || Lc(2 bytes) || data.  With len == 7 this is case 2E (Le only).
        if (len < 7) {
            return null;
        }
        int extendedLc = ((apdu[5] & 0xFF) << 8) | (apdu[6] & 0xFF);
        if (extendedLc == 0) {
            return new int[]{7, 0};
        }
        return len >= 7 + extendedLc ? new int[]{7, extendedLc} : null;
    }
}
