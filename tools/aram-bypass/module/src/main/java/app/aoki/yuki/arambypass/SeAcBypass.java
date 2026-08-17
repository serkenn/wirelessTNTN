package app.aoki.yuki.arambypass;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Forces the OMAPI access decision inside {@code com.android.se} to ALLOWED, so an app can open
 * logical channels to Secure Element applets that the SE's ARA-M does not list it for.
 *
 * Why this is needed at all: the GlobalPlatform access control check does not run in the calling
 * app, it runs in the SecureElementService process and is keyed on the *caller's signing
 * certificate hash* versus the rules stored on the card.  Neither root nor any runtime permission
 * changes either of those inputs, so the only way to get past it without touching the card is to
 * change the decision where it is made.
 *
 * Hook targets, read out of the device's own /system/app/SecureElement/SecureElement.apk
 * (versionName 17):
 * <pre>
 *   com.android.se.security.AccessControlEnforcer#setUpChannelAccess(...)  // channel open
 *       throws AccessControlException(mTag + "no APDU access allowed!")
 *   com.android.se.security.AccessControlEnforcer#checkCommand(...)        // every transmit()
 *   com.android.se.security.AccessControlEnforcer#getAccessRule(...)       // rule resolution
 *       throws SecurityException("An access rule with a different hash and all AIDs was
 *                                 found. (Case C)")
 *   com.android.se.security.ChannelAccess#getAccess() / getApduAccess()
 *                                        / getNFCEventAccess() / isUseApduFilter()
 *                                        / setApduAccess(ACCESS)
 *   com.android.se.security.ChannelAccess$ACCESS { ALLOWED, DENIED, UNDEFINED }
 * </pre>
 *
 * Opening a channel and exchanging APDUs on it are checked <em>separately</em>: hooking only the
 * channel-open path gets the SELECT through and then every subsequent transmit() still fails, so
 * both {@code checkCommand} and {@code getAccessRule} have to be covered too.  Card-side rules
 * make this concrete — an eSTK.me ships a rule for all AIDs bound to another app's certificate
 * hash, which resolves as "Case C" and denies.
 *
 * {@code getPrivilegeAccess()} is deliberately left alone — that gates privileged operations
 * rather than plain APDU exchange, and widening it buys nothing here.
 */
public class SeAcBypass implements IXposedHookLoadPackage {

    private static final String TAG = "AramBypass: ";
    private static final String TARGET_PACKAGE = "com.android.se";
    private static final String CHANNEL_ACCESS = "com.android.se.security.ChannelAccess";
    private static final String ACCESS_ENUM = "com.android.se.security.ChannelAccess$ACCESS";
    private static final String ENFORCER = "com.android.se.security.AccessControlEnforcer";

    @Override
    public void handleLoadPackage(LoadPackageParam lpp) {
        if (!TARGET_PACKAGE.equals(lpp.packageName)) {
            return;
        }
        try {
            final Class<?> channelAccess = XposedHelpers.findClass(CHANNEL_ACCESS, lpp.classLoader);
            final Object allowed = enumValue(
                    XposedHelpers.findClass(ACCESS_ENUM, lpp.classLoader), "ALLOWED");

            Class<?> enforcer = XposedHelpers.findClass(ENFORCER, lpp.classLoader);

            // Anything that is supposed to hand back a verdict hands back an ALLOWED one.
            XC_MethodHook permissiveVerdict = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object access = param.getThrowable() == null ? param.getResult() : null;
                    if (access == null) {
                        try {
                            access = XposedHelpers.newInstance(channelAccess);
                        } catch (Throwable t) {
                            // Could not synthesise a verdict, so leave the original outcome in
                            // place rather than returning something bogus: failing closed beats
                            // handing back a broken ChannelAccess.
                            XposedBridge.log(TAG + "could not build ChannelAccess: " + t);
                            return;
                        }
                    }
                    XposedHelpers.callMethod(access, "setApduAccess", allowed);
                    // setResult() also clears any pending throwable.
                    param.setResult(access);
                }
            };

            int hooks = 0;
            // Verdict readers.
            hooks += XposedBridge.hookAllMethods(channelAccess, "getAccess",
                    XC_MethodReplacement.returnConstant(allowed)).size();
            hooks += XposedBridge.hookAllMethods(channelAccess, "getApduAccess",
                    XC_MethodReplacement.returnConstant(allowed)).size();
            hooks += XposedBridge.hookAllMethods(channelAccess, "getNFCEventAccess",
                    XC_MethodReplacement.returnConstant(allowed)).size();
            hooks += XposedBridge.hookAllMethods(channelAccess, "isUseApduFilter",
                    XC_MethodReplacement.returnConstant(false)).size();

            // Channel open, and the rule resolution behind it.
            hooks += XposedBridge.hookAllMethods(enforcer, "setUpChannelAccess",
                    permissiveVerdict).size();
            hooks += XposedBridge.hookAllMethods(enforcer, "getAccessRule",
                    permissiveVerdict).size();

            // Per-APDU gate on transmit(); returns void, so a plain no-op replacement is enough.
            hooks += XposedBridge.hookAllMethods(enforcer, "checkCommand",
                    XC_MethodReplacement.returnConstant(null)).size();

            XposedBridge.log(TAG + "installed " + hooks + " hooks in " + TARGET_PACKAGE);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "failed to install hooks: " + t);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<Enum>) enumClass, name);
    }
}
