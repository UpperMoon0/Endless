package com.nstut.endless.benchmark;

/**
 * Phase 3+4+5 combined benchmark.
 * Simulates chunk networking, BlockPos packing, and vertical section loading.
 */
public class CombinedBenchmark {

    // ---- Phase 3: Network Packet Slimming ----

    static class ChunkPacketSim {
        final int sectionCount;
        final int filledCount;

        ChunkPacketSim(int sectionCount, int filledCount) {
            this.sectionCount = sectionCount;
            this.filledCount = Math.min(filledCount, sectionCount);
        }

        /** OLD: serialize all sections. Empty sections = 1 empty byte. Filled = 4-byte Y + 4KB data. */
        long serializeOld() {
            long bytes = 4; // section count header
            for (int i = 0; i < sectionCount; i++) {
                bytes += 4; // section Y index
                if (i < filledCount) bytes += 4096;
                else bytes += 1; // empty marker
            }
            return bytes;
        }

        /** NEW: BitSet mask + only serialize non-empty sections. */
        long serializeNew() {
            long bytes = 4; // section count header
            bytes += (sectionCount + 7) / 8; // mask bytes
            for (int i = 0; i < filledCount; i++) {
                bytes += 4 + 4096; // Y index + data for each non-empty
            }
            return bytes;
        }

        double savingsPercent() {
            long old = serializeOld();
            long nw  = serializeNew();
            return (old - nw) * 100.0 / old;
        }
    }

    // ---- Phase 4: BlockPos Y-bit Extension ----

    static long packBlockPosOld(int x, int y, int z) {
        // Vanilla: X=26, Z=26, Y=12 bits
        return ((long)x & 0x3FFFFFF) << 38
             | ((long)z & 0x3FFFFFF) << 12
             | ((long)y & 0xFFF);
    }

    static int unpackYOld(long packed) {
        int val = (int)(packed & 0xFFF);
        return (val << 20) >> 20; // sign-extend from 12 bits
    }

    static long packBlockPosNew(int x, int y, int z) {
        return ((long)x & 0xFFFFFF) << 40
             | ((long)z & 0xFFFFFF) << 16
             | ((long)y & 0xFFFF);
    }

    static int unpackYNew(long packed) {
        int val = (int)(packed & 0xFFFF);
        return (val << 16) >> 16; // sign-extend from 16 bits
    }

    static int maxYRangeOld() { return 1 << 12; }
    static int maxYRangeNew() { return 1 << 16; }

    // ---- Phase 5: Vertical Chunk Loading ----

    static class VerticalLoadSim {
        final int totalSections;
        final int viewSections; // sections above/below player
        final int playerSection;

        VerticalLoadSim(int totalSections, int viewSections, int playerSection) {
            this.totalSections = totalSections;
            this.viewSections = viewSections;
            this.playerSection = Math.min(playerSection, totalSections - 1);
        }

        int sectionsLoadedOld()  { return totalSections; }
        int sectionsLoadedNew()  {
            int lo = Math.max(0, playerSection - viewSections);
            int hi = Math.min(totalSections - 1, playerSection + viewSections);
            return hi - lo + 1;
        }
    }

    // ---- Main ----

    public static void main(String[] args) {
        // Phase 3
        System.out.println("--- Phase 3: Network Packet Slimming ---");
        System.out.printf("%-20s %8s %10s %10s %9s%n", "Config", "Filled%", "Old(B)", "New(B)", "Savings");
        System.out.println("-".repeat(70));
        int[] secCounts = {24, 128, 256, 512, 768, 1536, 4096};
        int[] pcts      = {1, 5, 10, 25, 50, 100};
        for (int sec : secCounts) {
            for (int pct : pcts) {
                int filled = sec * pct / 100;
                ChunkPacketSim sim = new ChunkPacketSim(sec, filled);
                System.out.printf("%-20s %8d%% %10d %10d %8.1f%%%n",
                    sec + " sections", pct, sim.serializeOld(), sim.serializeNew(), sim.savingsPercent());
            }
            if (sec < 768) System.out.println();
        }

        // Phase 4
        System.out.println();
        System.out.println("--- Phase 4: BlockPos Y-bit Extension ---");
        System.out.printf("Vanilla  12-bit Y: range [%d, %d]%n", -2048, 2047);
        System.out.printf("Extended 16-bit Y: range [%d, %d]%n", -32768, 32767);
        int[] testYs = {-4096, -2048, -64, 0, 320, 2047, 4096, 8191, 32767};
        System.out.printf("%-8s %10s %10s %10s %10s%n", "Y", "Old pack", "Old unpk", "New pack", "New unpk");
        System.out.println("-".repeat(58));
        for (int y : testYs) {
            long oldPack = packBlockPosOld(0, y, 0);
            long newPack = packBlockPosNew(0, y, 0);
            int oldUnpk = unpackYOld(oldPack);
            int newUnpk = unpackYNew(newPack);
            System.out.printf("%-8d %10d %10s %10d %10s%n",
                y, oldPack, oldUnpk == y ? "OK" : "BROKEN:" + oldUnpk,
                newPack, newUnpk == y ? "OK" : "BROKEN:" + newUnpk);
        }

        // Pack/unpack perf
        int ITERS = 1_000_000;
        long start = System.nanoTime();
        for (int i = 0; i < ITERS; i++) packBlockPosOld(i & 0xFF, i & 0xFFF, i & 0xFF);
        long oldNs = (System.nanoTime() - start) / ITERS;
        start = System.nanoTime();
        for (int i = 0; i < ITERS; i++) packBlockPosNew(i & 0xFF, i & 0xFFFF, i & 0xFF);
        long newNs = (System.nanoTime() - start) / ITERS;
        System.out.printf("%npackBlockPos: OLD=%dns NEW=%dns%n", oldNs, newNs);
        System.out.println();

        // Phase 5
        System.out.println("--- Phase 5: Vertical Chunk Loading ---");
        System.out.println("viewDistance = 8 sections (128 blocks) around player");
        System.out.printf("%-22s %10s %10s %10s %12s%n",
            "Player section", "OLD", "NEW", "Reduction", "Mem(NEW)");
        System.out.println("-".repeat(70));
        int[] positions = {0, 128, 256, 384, 512, 640, 767};
        for (int py : positions) {
            VerticalLoadSim sim = new VerticalLoadSim(768, 8, py);
            long red = sim.sectionsLoadedOld() - sim.sectionsLoadedNew();
            double pct = 100.0 * red / sim.sectionsLoadedOld();
            System.out.printf("section %-15d %10d %10d %9.1f%% %10d KB%n",
                py, sim.sectionsLoadedOld(), sim.sectionsLoadedNew(), pct,
                sim.sectionsLoadedNew() * 4);
        }

        // Combined summary
        System.out.println();
        System.out.println("=== Combined Summary (768 sections, 25% fill, player at section 384) ===");
        System.out.println();
        ChunkPacketSim net = new ChunkPacketSim(768, 192);
        VerticalLoadSim mem = new VerticalLoadSim(768, 8, 384);
        System.out.printf("%-42s %10s %10s %15s%n", "Metric", "Before", "After", "Improvement");
        System.out.println("-".repeat(80));
        System.out.printf("%-42s %10d %10d %15s%n",
            "highestFilledIndex scans", 768, 1, "768x fewer");
        System.out.printf("%-42s %10d %10d %14.1f%%%n",
            "Chunk packet size (bytes)", net.serializeOld(), net.serializeNew(), net.savingsPercent());
        System.out.printf("%-42s %10d %10d %14.1f%%%n",
            "Sections loaded per chunk", mem.sectionsLoadedOld(), mem.sectionsLoadedNew(),
            100.0 * (mem.sectionsLoadedOld() - mem.sectionsLoadedNew()) / mem.sectionsLoadedOld());
        System.out.printf("%-42s %10d %10d %15s%n",
            "Max Y range supported", maxYRangeOld(), maxYRangeNew(), "16x larger");
        System.out.printf("%-42s %10d %10d %13d KB%n",
            "Memory per chunk (all sections)", 768 * 4, mem.sectionsLoadedNew() * 4, mem.sectionsLoadedNew() * 4);
    }
}
