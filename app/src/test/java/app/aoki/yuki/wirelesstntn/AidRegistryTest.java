package app.aoki.yuki.wirelesstntn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class AidRegistryTest {

    @Test
    public void rejectsAidsShorterThanFiveBytes() {
        // This is the rule that made the original "A0*" / "F0*" filters throw
        // IllegalArgumentException at install time.
        assertFalse(AidRegistry.isValidAid("A0*"));
        assertFalse(AidRegistry.isValidAid("A0000000*"));
        assertTrue(AidRegistry.isValidAid("A000000151*"));
    }

    @Test
    public void acceptsExactPrefixAndSubsetForms() {
        assertTrue(AidRegistry.isValidAid("D2760000850101"));
        assertTrue(AidRegistry.isValidAid("D276000085*"));
        assertTrue(AidRegistry.isValidAid("D276000085#"));
    }

    @Test
    public void rejectsOddLengthNonHexAndOversizedAids() {
        assertFalse(AidRegistry.isValidAid("A00000015*"));           // odd number of hex digits
        assertFalse(AidRegistry.isValidAid("A0000001Z1*"));          // not hex
        assertFalse(AidRegistry.isValidAid(null));
        assertFalse(AidRegistry.isValidAid("A0000001510000000000000000000000000000")); // > 16 B
    }

    @Test
    public void parseNormalizesSplitsAndDeduplicates() {
        AidRegistry.ParseResult result =
                AidRegistry.parse("a000000151*, A000000151*\n d276000085*;A000000087*");
        assertEquals(Arrays.asList("A000000151*", "D276000085*", "A000000087*"), result.accepted);
        assertFalse(result.hasRejections());
    }

    @Test
    public void parseCollectsRejectedTokensInsteadOfFailing() {
        AidRegistry.ParseResult result = AidRegistry.parse("A000000151* A0* nonsense");
        assertEquals(Arrays.asList("A000000151*"), result.accepted);
        assertEquals(Arrays.asList("A0*", "nonsense"), result.rejected);
    }

    @Test
    public void parseHandlesEmptyInput() {
        assertTrue(AidRegistry.parse("").accepted.isEmpty());
        assertTrue(AidRegistry.parse("   \n ").accepted.isEmpty());
        assertTrue(AidRegistry.parse(null).accepted.isEmpty());
    }

    @Test
    public void everyShippedAidIsRegisterable() {
        for (String aid : AidRegistry.DEFAULT_AIDS) {
            assertTrue(aid + " must be a valid AID", AidRegistry.isValidAid(aid));
        }
        for (String aid : AidRegistry.PAYMENT_AIDS) {
            assertTrue(aid + " must be a valid AID", AidRegistry.isValidAid(aid));
        }
    }

    @Test
    public void formatRoundTripsThroughParse() {
        String formatted = AidRegistry.format(AidRegistry.DEFAULT_AIDS);
        assertEquals(AidRegistry.DEFAULT_AIDS, AidRegistry.parse(formatted).accepted);
    }
}
