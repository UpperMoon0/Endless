package com.nstut.endless.testing;

import com.nstut.endless.heights.EndlessHeights;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * Client-side assertions for the live-join test, armed by the
 * {@code endless.liveJoinTest} system property. Used by
 * {@code tools/live_join_test.py} to detect regressions in the
 * Fabric/Forge range-sync path before the PR merges.
 *
 * <p>Two assertions exist because each catches a different failure class:</p>
 *
 * <ol>
 *   <li>{@link #assertPreLoginRange()} runs at the {@code handleLogin} HEAD,
 *     before vanilla constructs the client world. A freshly constructed
 *     ClientLevel must never see the wrong range; a wrong value here means
 *     the login-phase handshake did not run or applied too late. This must
 *     fail even if a later global fix would paper over it.</li>
 *   <li>{@link #tick()} runs from the client tick event after chunks have
 *     flowed, asserting the ClientLevel itself reports the server's layout
 *     (min build height and height, i.e. the section arrays were sized
 *     correctly).</li>
 * </ol>
 *
 * <p>The expected range is a hardcoded constant on purpose: the live-join
 * test server uses a known config, and any other range means the sync was
 * skipped, the wrong packet was sent, or the client's local config leaked
 * through.</p>
 */
public final class LiveJoinTest {

    /**
     * Server-side config baked into the live-join test runner. The test server
     * writes an {@code endless.json} with this range; the test client uses
     * vanilla config and must end up with these bounds after joining.
     */
    public static final int EXPECTED_MIN_BUILD_HEIGHT = -1024;
    public static final int EXPECTED_MAX_BUILD_HEIGHT = 1024;

    public static final String PASS_MARKER = "ENDLESS_LIVE_JOIN_TEST_PASS";
    public static final String FAIL_MARKER = "ENDLESS_LIVE_JOIN_TEST_FAIL";
    public static final String PRE_LOGIN_PASS_MARKER = "ENDLESS_PRE_LOGIN_RANGE_PASS";
    public static final String PRE_LOGIN_FAIL_MARKER = "ENDLESS_PRE_LOGIN_RANGE_FAIL";

    public static final String SYSTEM_PROPERTY = "endless.liveJoinTest";

    private static boolean armed;
    private static boolean preLoginChecked;
    private static int ticksWithLevel;

    private LiveJoinTest() {
    }

    public static boolean isArmed() {
        if (armed) {
            return true;
        }
        String value = System.getProperty(SYSTEM_PROPERTY);
        if ("true".equalsIgnoreCase(value)) {
            armed = true;
        }
        return armed;
    }

    /**
     * Called from {@code ClientPacketListener.handleLogin} HEAD, before
     * vanilla constructs the ClientLevel. The login-phase sync must already
     * have applied the server's authoritative range at this point.
     */
    public static void assertPreLoginRange() {
        if (!isArmed() || preLoginChecked) {
            return;
        }
        preLoginChecked = true;
        int endlessMin = EndlessHeights.getMinBuildHeight();
        int endlessMax = EndlessHeights.getMaxBuildHeight();
        if (endlessMin == EXPECTED_MIN_BUILD_HEIGHT && endlessMax == EXPECTED_MAX_BUILD_HEIGHT) {
            System.out.println(PRE_LOGIN_PASS_MARKER
                + " endlessMin=" + endlessMin
                + " endlessMax=" + endlessMax);
        } else {
            System.out.println(FAIL_MARKER
                + " phase=preLogin"
                + " endlessMin=" + endlessMin
                + " endlessMax=" + endlessMax
                + " expectedMin=" + EXPECTED_MIN_BUILD_HEIGHT
                + " expectedMax=" + EXPECTED_MAX_BUILD_HEIGHT);
        }
    }

    /**
     * Call from each loader's client tick event. Returns {@code true} once
     * the test has produced a final line; the caller should then stop calling
     * this and the run-task will exit naturally.
     */
    public static boolean tick() {
        if (!isArmed() || !preLoginChecked) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return false;
        }
        // The pre-login assertion covers the world-construction boundary; the
        // delay here only lets the first chunks flow so the check below also
        // proves the section arrays were sized with the synced range.
        ticksWithLevel++;
        if (ticksWithLevel < 40) {
            return false;
        }

        Level level = mc.level;
        int levelMin = level.getMinBuildHeight();
        int levelHeight = level.getHeight();
        int endlessMin = EndlessHeights.getMinBuildHeight();
        int endlessMax = EndlessHeights.getMaxBuildHeight();
        int expectedHeight = EXPECTED_MAX_BUILD_HEIGHT - EXPECTED_MIN_BUILD_HEIGHT;

        if (levelMin == EXPECTED_MIN_BUILD_HEIGHT
            && levelHeight == expectedHeight
            && endlessMin == EXPECTED_MIN_BUILD_HEIGHT
            && endlessMax == EXPECTED_MAX_BUILD_HEIGHT) {
            System.out.println(PASS_MARKER
                + " min=" + levelMin
                + " max=" + (levelMin + levelHeight)
                + " height=" + levelHeight
                + " endlessMin=" + endlessMin
                + " endlessMax=" + endlessMax);
            mc.stop();
            return true;
        }
        System.out.println(FAIL_MARKER
            + " phase=postJoin"
            + " levelMin=" + levelMin
            + " levelHeight=" + levelHeight
            + " endlessMin=" + endlessMin
            + " endlessMax=" + endlessMax
            + " expectedMin=" + EXPECTED_MIN_BUILD_HEIGHT
            + " expectedMax=" + EXPECTED_MAX_BUILD_HEIGHT
            + " expectedHeight=" + expectedHeight);
        mc.stop();
        return true;
    }
}
