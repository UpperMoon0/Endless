package com.nstut.endless.testing;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** Client-side assertions used by tools/live_join_test.py. */
public final class LiveJoinTest {
    public static final int DEFAULT_EXPECTED_MIN_BUILD_HEIGHT = -1024;
    public static final int DEFAULT_EXPECTED_MAX_BUILD_HEIGHT = 1024;

    private static final int EXPECTED_MIN_BUILD_HEIGHT =
        intProperty("endless.liveJoinTest.expectedMin", DEFAULT_EXPECTED_MIN_BUILD_HEIGHT);
    private static final int EXPECTED_MAX_BUILD_HEIGHT =
        intProperty("endless.liveJoinTest.expectedMax", DEFAULT_EXPECTED_MAX_BUILD_HEIGHT);
    private static final boolean EXPECT_LOGICAL =
        Boolean.parseBoolean(System.getProperty("endless.liveJoinTest.expectLogical", "true"));
    private static final boolean HIGH_Y_TEST =
        Boolean.parseBoolean(System.getProperty("endless.liveJoinTest.highY", "false"));

    public static final String PASS_MARKER = "ENDLESS_LIVE_JOIN_TEST_PASS";
    public static final String FAIL_MARKER = "ENDLESS_LIVE_JOIN_TEST_FAIL";
    public static final String PRE_LOGIN_PASS_MARKER = "ENDLESS_PRE_LOGIN_RANGE_PASS";
    public static final String PRESEED_MARKER = "ENDLESS_LIVE_JOIN_TEST_PRESEEDED_STALE_RANGE";
    public static final String HIGH_Y_PASS_MARKER = "ENDLESS_HIGH_Y_CLIENT_PASS";

    public static final String SYSTEM_PROPERTY = "endless.liveJoinTest";
    public static final String PRESEED_STALE_PROPERTY = "endless.liveJoinTest.preseedStaleRange";

    private static boolean armed;
    private static boolean staleRangePreseeded;
    private static boolean preLoginChecked;
    private static int ticksWithLevel;

    private LiveJoinTest() {}

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
        if ("true".equalsIgnoreCase(System.getProperty(SYSTEM_PROPERTY))) {
            armed = true;
        }
        return armed;
    }

    public static void preseedStaleRangeIfRequested() {
        if (!isArmed() || staleRangePreseeded
            || !Boolean.parseBoolean(System.getProperty(PRESEED_STALE_PROPERTY, "false"))) {
            return;
        }
        staleRangePreseeded = true;
        EndlessHeights.applyEffective(
            DEFAULT_EXPECTED_MIN_BUILD_HEIGHT,
            DEFAULT_EXPECTED_MAX_BUILD_HEIGHT);
        EndlessLogicalHeights.activate();
        System.out.println(PRESEED_MARKER
            + " min=" + EndlessHeights.getMinBuildHeight()
            + " max=" + EndlessHeights.getMaxBuildHeight());
    }

    /** Assert server-authoritative state before vanilla constructs ClientLevel. */
    public static void assertPreLoginRange() {
        if (!isArmed() || preLoginChecked) {
            return;
        }
        preLoginChecked = true;

        if (Boolean.parseBoolean(System.getProperty(PRESEED_STALE_PROPERTY, "false"))
            && !staleRangePreseeded) {
            fail("stalePreseedMissing", "");
            return;
        }

        int endlessMin = EndlessHeights.getMinBuildHeight();
        int endlessMax = EndlessHeights.getMaxBuildHeight();
        boolean logical = EndlessLogicalHeights.isActive();
        if (endlessMin == EXPECTED_MIN_BUILD_HEIGHT
            && endlessMax == EXPECTED_MAX_BUILD_HEIGHT
            && logical == EXPECT_LOGICAL) {
            System.out.println(PRE_LOGIN_PASS_MARKER
                + " endlessMin=" + endlessMin
                + " endlessMax=" + endlessMax
                + " logical=" + logical);
        } else {
            fail("preLogin",
                " endlessMin=" + endlessMin
                    + " endlessMax=" + endlessMax
                    + " logical=" + logical
                    + " expectedLogical=" + EXPECT_LOGICAL);
        }
    }

    public static boolean tick() {
        if (!isArmed() || !preLoginChecked) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return false;
        }
        ticksWithLevel++;

        Level level = mc.level;
        int levelMin = level.getMinBuildHeight();
        int levelHeight = level.getHeight();
        int endlessMin = EndlessHeights.getMinBuildHeight();
        int endlessMax = EndlessHeights.getMaxBuildHeight();
        int expectedHeight = EXPECTED_MAX_BUILD_HEIGHT - EXPECTED_MIN_BUILD_HEIGHT;
        boolean logical = EndlessLogicalHeights.isActive();

        if (levelMin != EXPECTED_MIN_BUILD_HEIGHT
            || levelHeight != expectedHeight
            || endlessMin != EXPECTED_MIN_BUILD_HEIGHT
            || endlessMax != EXPECTED_MAX_BUILD_HEIGHT
            || logical != EXPECT_LOGICAL) {
            if (ticksWithLevel >= 40) {
                fail("postJoin",
                    " levelMin=" + levelMin
                        + " levelHeight=" + levelHeight
                        + " endlessMin=" + endlessMin
                        + " endlessMax=" + endlessMax
                        + " logical=" + logical
                        + " expectedLogical=" + EXPECT_LOGICAL);
                mc.stop();
                return true;
            }
            return false;
        }

        if (HIGH_Y_TEST) {
            BlockPos high = LiveHighYServerTest.TEST_POS;
            boolean arrived = mc.player != null
                && Math.abs(mc.player.getY() - (LiveHighYServerTest.TEST_Y + 2.0D)) < 8.0D;
            boolean buildable = !level.isOutsideBuildHeight(high);
            boolean blockVisible = level.getBlockState(high).is(Blocks.DIAMOND_BLOCK);
            if (arrived && buildable && blockVisible) {
                System.out.println(HIGH_Y_PASS_MARKER
                    + " playerY=" + mc.player.getY()
                    + " blockY=" + high.getY());
                pass(levelMin, levelHeight, endlessMin, endlessMax, logical);
                mc.stop();
                return true;
            }
            if (ticksWithLevel < 240) {
                return false;
            }
            fail("highY",
                " arrived=" + arrived
                    + " buildable=" + buildable
                    + " blockVisible=" + blockVisible
                    + " playerY=" + (mc.player == null ? "null" : mc.player.getY()));
            mc.stop();
            return true;
        }

        if (ticksWithLevel < 40) {
            return false;
        }
        pass(levelMin, levelHeight, endlessMin, endlessMax, logical);
        mc.stop();
        return true;
    }

    private static void pass(int levelMin, int levelHeight, int endlessMin, int endlessMax, boolean logical) {
        System.out.println(PASS_MARKER
            + " min=" + levelMin
            + " max=" + (levelMin + levelHeight)
            + " height=" + levelHeight
            + " endlessMin=" + endlessMin
            + " endlessMax=" + endlessMax
            + " logical=" + logical);
    }

    private static void fail(String phase, String details) {
        System.out.println(FAIL_MARKER
            + " phase=" + phase
            + details
            + " expectedMin=" + EXPECTED_MIN_BUILD_HEIGHT
            + " expectedMax=" + EXPECTED_MAX_BUILD_HEIGHT);
    }
}
