package app.aoki.yuki.wirelesstntn;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.se.omapi.Channel;
import android.se.omapi.Session;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.NoSuchElementException;

/**
 * The point of these tests is the failure mapping: every OMAPI failure has to come back as a
 * status word, because an exception escaping onto the APDU executor takes the process down and
 * leaves the reader waiting for a response that never arrives.
 */
public class ApduPassthroughControllerTest {

    private Session session;
    private Channel channel;
    private ApduPassthroughController controller;

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Before
    public void setUp() {
        session = mock(Session.class);
        channel = mock(Channel.class);
        controller = new ApduPassthroughController(session);
    }

    private void givenOpenChannel() throws IOException {
        when(session.openLogicalChannel(any(), anyByte())).thenReturn(channel);
        when(channel.getSelectResponse()).thenReturn(hex("6F108408A0000001519000"));
        when(channel.isOpen()).thenReturn(true);
        controller.process(hex("00A4040007A0000001515000"));
    }

    @Test
    public void selectForwardsAidAndP2AndReturnsSelectResponse() throws IOException {
        byte[] selectResponse = hex("6F0A8408A00000015100009000");
        when(session.openLogicalChannel(any(), anyByte())).thenReturn(channel);
        when(channel.getSelectResponse()).thenReturn(selectResponse);

        // SELECT with P2 = 0x0C (no response data requested by the reader).
        byte[] response = controller.process(hex("00A4040C07A0000001515000"));

        ArgumentCaptor<byte[]> aid = ArgumentCaptor.forClass(byte[].class);
        verify(session).openLogicalChannel(aid.capture(), eq((byte) 0x0C));
        assertArrayEquals(hex("A0000001515000"), aid.getValue());
        assertArrayEquals(selectResponse, response);
    }

    @Test
    public void selectWithEmptyAidSelectsTheDefaultApplet() throws IOException {
        when(session.openLogicalChannel(any(), anyByte())).thenReturn(channel);
        when(channel.getSelectResponse()).thenReturn(null);

        byte[] response = controller.process(hex("00A4040000"));

        verify(session).openLogicalChannel(null, (byte) 0x00);
        assertArrayEquals(ApduStatus.SW_OK, response);
    }

    @Test
    public void selectClosesThePreviousChannelFirst() throws IOException {
        givenOpenChannel();
        Channel second = mock(Channel.class);
        when(session.openLogicalChannel(any(), anyByte())).thenReturn(second);

        controller.process(hex("00A4040007A0000000870000"));

        verify(channel).close();
    }

    @Test
    public void selectReturns6A82WhenTheSeProvidesNoChannel() throws IOException {
        when(session.openLogicalChannel(any(), anyByte())).thenReturn(null);
        assertArrayEquals(ApduStatus.SW_FILE_NOT_FOUND,
                controller.process(hex("00A4040007A0000001515000")));
    }

    @Test
    public void selectMapsNoSuchElementTo6A82() throws IOException {
        when(session.openLogicalChannel(any(), anyByte()))
                .thenThrow(new NoSuchElementException("no such applet"));
        assertArrayEquals(ApduStatus.SW_FILE_NOT_FOUND,
                controller.process(hex("00A4040007A0000001515000")));
    }

    @Test
    public void selectMapsSecurityExceptionTo6982() throws IOException {
        when(session.openLogicalChannel(any(), anyByte()))
                .thenThrow(new SecurityException("ARA-M refused"));
        assertArrayEquals(ApduStatus.SW_SECURITY_NOT_SATISFIED,
                controller.process(hex("00A4040007A0000001515000")));
    }

    @Test
    public void selectMapsIoExceptionTo6F00() throws IOException {
        when(session.openLogicalChannel(any(), anyByte())).thenThrow(new IOException("SE gone"));
        assertArrayEquals(ApduStatus.SW_INTERNAL_ERROR,
                controller.process(hex("00A4040007A0000001515000")));
    }

    @Test
    public void manageChannelIsRefusedInsteadOfForwarded() throws IOException {
        givenOpenChannel();
        assertArrayEquals(ApduStatus.SW_INS_NOT_SUPPORTED, controller.process(hex("0070000001")));
        verify(channel, never()).transmit(any());
    }

    @Test
    public void apduWithoutAnOpenChannelReturns6985() {
        assertArrayEquals(ApduStatus.SW_CONDITIONS_NOT_SATISFIED,
                controller.process(hex("00B0000000")));
    }

    @Test
    public void apduIsForwardedThroughTheOpenChannel() throws IOException {
        givenOpenChannel();
        byte[] expected = hex("0102039000");
        when(channel.transmit(any())).thenReturn(expected);

        assertArrayEquals(expected, controller.process(hex("00B0000000")));
        verify(channel).transmit(hex("00B0000000"));
    }

    @Test
    public void transmitFailureBecomesAStatusWordRatherThanAnException() throws IOException {
        givenOpenChannel();
        when(channel.transmit(any())).thenThrow(new IllegalStateException("channel closed"));
        assertArrayEquals(ApduStatus.SW_CONDITIONS_NOT_SATISFIED,
                controller.process(hex("00B0000000")));
    }

    @Test
    public void truncatedSeResponseBecomes6F00() throws IOException {
        givenOpenChannel();
        when(channel.transmit(any())).thenReturn(new byte[]{0x00});
        assertArrayEquals(ApduStatus.SW_INTERNAL_ERROR, controller.process(hex("00B0000000")));
    }

    @Test
    public void malformedApdusAreAnswered() {
        assertArrayEquals(ApduStatus.SW_WRONG_LENGTH, controller.process(null));
        assertArrayEquals(ApduStatus.SW_WRONG_LENGTH, controller.process(hex("00A4")));
    }

    // ---------------------------------------------------------------------------------------
    // Parsing helpers
    // ---------------------------------------------------------------------------------------

    @Test
    public void selectIsRecognisedOnLogicalChannelAndSecureMessagingClasses() {
        assertTrue(ApduPassthroughController.isSelectByAid(hex("00A4040000")));
        assertTrue(ApduPassthroughController.isSelectByAid(hex("01A4040000")));
        assertTrue(ApduPassthroughController.isSelectByAid(hex("0CA4040000")));
        assertTrue(ApduPassthroughController.isSelectByAid(hex("40A4040000")));
        // SELECT by file id is not an AID selection.
        assertFalse(ApduPassthroughController.isSelectByAid(hex("00A4000000")));
        // Proprietary class byte.
        assertFalse(ApduPassthroughController.isSelectByAid(hex("80A4040000")));
    }

    @Test
    public void readerSuppliedChannelNumberIsStripped() {
        assertArrayEquals(hex("00B0000000"),
                ApduPassthroughController.normalizeChannelNumber(hex("01B0000000")));
        byte[] untouched = hex("00B0000000");
        assertSame(untouched, ApduPassthroughController.normalizeChannelNumber(untouched));
    }

    @Test
    public void commandDataRangeUnderstandsShortAndExtendedLengths() {
        assertArrayEquals(new int[]{4, 0},
                ApduPassthroughController.commandDataRange(hex("00A4040000")));
        assertArrayEquals(new int[]{5, 7},
                ApduPassthroughController.commandDataRange(hex("00A4040407A000000151500000")));
        assertArrayEquals(new int[]{7, 7},
                ApduPassthroughController.commandDataRange(hex("00A40404000007A0000001515000")));
        // Lc longer than the APDU actually is.
        assertNull(ApduPassthroughController.commandDataRange(hex("00A404040FA0000001")));
    }
}
