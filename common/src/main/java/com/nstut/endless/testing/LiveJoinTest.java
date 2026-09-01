package com.nstut.endless.testing;

import com.nstut.endless.heights.EndlessHeights;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/** Client-side assertions used by tools/live_join_test.py. */
public final class LiveJoinTest {

    public static final int DEFAULT_EXPECTED_MIN_BUILD_HEIGHT = -1024;
    public static final int DEFAULT_EXPECTED_MAX_BUILD_HEIGHT = 1024;

    private static final int EXPECTED_MIN_BUILD_HEIGHT =
        intProperty("endless.liveJoinTest.expectedMin", DEFAULT_EXPECTED_MIN_BUILD_HEIGHT);
    private static final int EXPECTED_MAX_BUILD_HEIGHT =
        intProperty("endless.liveJoinTest.expectedMax", DEFAULT_EXPECTED_MAX_BUILD_HEIGHT);

    public static final String PASS_MARKER = "ENDLESS_LIVE_JOIN_TEST_PASS";
    public static final String FAIL_MARKER = "ENDLESS_LIVE_JOIN_TEST_FAIL";
    public static final String PRE_LOGIN_PASS_MARKER = "ENDLESS_PRE_LOGIN_RANGE_PASS";
    public static final String PRE_LOGIN_FAIL_MARKER = "ENDLESS_PRE_LOGIN_RANGE_FAIL";
    public static final String PRESEED_MARKER = "ENDLESS_LIVE_JOIN_TEST_PRESEEDED_STALE_RANGE";

    public static final String SYSTEM_PROPERTY = "endless.liveJoinTest";
    public static final String PRESEED_STALE_PROPERTY = "endless.liveJoinTest.preseedStaleRange";

    private static boolean armed;
    private static boolean staleRangePreseeded;
    private static boolean preLoginChecked;
    private static int ticksWithLevel;

    private LiveJoinTest() {
    }

    private static int intProperty(String key, int fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            System.err.println("Endless: invalid " + key + " value '" + raw + "'; using " + fallback);
            return fallback;
        }
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

    /** Seed applied=true with an extended range before the tested connection. */
    public static void preseedStaleRangeIfRequested() {
        if (!isArmed() || staleRangePreseeded
            || !Boolean.parseBoolean(System.getProperty(PRESEED_STALE_PROPERTY, "false"))) {
            return;
        }
        staleRangePreseeded = true;
        EndlessHeights.applyEffective(
            DEFAULT_EXPECTED_MIN_BUILD_HEIGHT,
            DEFAULT_EXPECTED_MAX_BUILD_HEIGHT
        );
        System.out.println(PRESEED_MARKER
            + " min=" + EndlessHeights.getMinBuildHeight()
            + " max=" + EndlessHeights.getMaxBuildHeight());
    }

    /** Assert the effective range before vanilla constructs ClientLevel. */
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

    /** Assert the constructed ClientLevel after chunks have begun flowing. */
    public static boolean tick() {
        if (!isArmed() || !preLoginChecked) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return false;
        }
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
