package com.nstut.endless.testing;

import com.nstut.endless.heights.EndlessHeights;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * One-shot client-side check that runs after the player has joined a server
 * when the {@code endless.liveJoinTest} system property is set. Asserts that
 * the effective build range the server provided matches the expected range
 * baked into the live-join test, prints a {@code PASS} or {@code FAIL} line,
 * and shuts the client down. Used by {@code tools/live_join_test.py} to
 * detect regressions in the Fabric/Forge range-sync path before the PR
 * merges.
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

    public static final String SYSTEM_PROPERTY = "endless.liveJoinTest";

    private static boolean armed;
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
     * Call from each loader's client tick event. Returns {@code true} once
     * the test has produced a final line; the caller should then stop calling
     * this and (on Fabric) the run-task will exit naturally.
     */
    public static boolean tick() {
        if (!isArmed()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return false;
        }
        // Wait for chunks to actually flow before checking the level range:
        // a freshly-constructed ClientLevel briefly exposes the vanilla range
        // before the server's login packet or the login-phase sync arrives.
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
