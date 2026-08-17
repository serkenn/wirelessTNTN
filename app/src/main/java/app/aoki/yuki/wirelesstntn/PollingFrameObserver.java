package app.aoki.yuki.wirelesstntn;

import android.nfc.cardemulation.PollingFrame;

import java.util.List;

/**
 * Classifies the polling frames delivered while observe mode is on.
 *
 * Frames arrive in batches, several times per second while a reader's field is present, so the
 * service is told about edges (field on/off, first ISO-DEP poll) rather than about every frame.
 */
public class PollingFrameObserver {

    public interface Callback {
        /** An ISO-DEP (type A/B) reader is polling: the transaction may be accepted. */
        void onReaderDetected();

        /** The reader's field appeared. */
        void onFieldOn();

        /** The reader's field disappeared. */
        void onFieldOff();
    }

    private final Callback callback;

    public PollingFrameObserver(Callback callback) {
        this.callback = callback;
    }

    public void onPollingFrames(List<PollingFrame> frames) {
        if (frames == null) {
            return;
        }
        for (PollingFrame frame : frames) {
            int type = frame.getType();
            AppLog.d("PollingFrame type=" + typeToString(type));

            switch (type) {
                case PollingFrame.POLLING_LOOP_TYPE_ON:
                    callback.onFieldOn();
                    break;
                case PollingFrame.POLLING_LOOP_TYPE_OFF:
                    callback.onFieldOff();
                    break;
                case PollingFrame.POLLING_LOOP_TYPE_A:
                case PollingFrame.POLLING_LOOP_TYPE_B:
                    callback.onReaderDetected();
                    break;
                default:
                    // Type F and unknown frames are logged only: this passthrough speaks ISO-DEP.
                    break;
            }
        }
    }

    static String typeToString(int type) {
        switch (type) {
            case PollingFrame.POLLING_LOOP_TYPE_A: return "A";
            case PollingFrame.POLLING_LOOP_TYPE_B: return "B";
            case PollingFrame.POLLING_LOOP_TYPE_F: return "F";
            case PollingFrame.POLLING_LOOP_TYPE_ON: return "ON";
            case PollingFrame.POLLING_LOOP_TYPE_OFF: return "OFF";
            case PollingFrame.POLLING_LOOP_TYPE_UNKNOWN: return "UNKNOWN";
            default: return "0x" + Integer.toHexString(type);
        }
    }
}
