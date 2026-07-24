package com.nstut.endless.benchmark;

import java.util.BitSet;

/**
 * Simulates ChunkAccess section operations for benchmarking old vs new implementations.
 * No Minecraft dependencies — pure Java.
 */
public class ChunkBenchmark {

    public static class Result {
        public final String label;
        public final int sectionCount;
        public final int filledSections;
        public final long highestFilledNs;
        public final long isYSpaceEmptyNs;
        public final long findBlocksNs;
        public final long memoryBytes;

        Result(String label, int sectionCount, int filledSections,
               long highestNs, long isEmptyNs, long findBlocksNs, long mem) {
            this.label = label;
            this.sectionCount = sectionCount;
            this.filledSections = filledSections;
            this.highestFilledNs = highestNs;
            this.isYSpaceEmptyNs = isEmptyNs;
            this.findBlocksNs = findBlocksNs;
            this.memoryBytes = mem;
        }

        public double micros(long ns) { return ns / 1_000.0; }

        @Override
        public String toString() {
            return String.format(
                "%-30s sections=%4d filled=%-4d highest=%7.1fµs isEmpty=%7.1fµs findBlocks=%7.1fµs mem=%dKB",
                label, sectionCount, filledSections,
                micros(highestFilledNs), micros(isYSpaceEmptyNs),
                micros(findBlocksNs), memoryBytes / 1024
            );
        }
    }

    // ---- Old impl: scan array top to bottom ----
    static class OldImpl {
        final Object[] sections;

        OldImpl(int count) {
            sections = new Object[count];
        }

        void fillSection(int idx) {
            sections[idx] = new Object(); // non-null = non-empty
        }

        int getHighestFilled() {
            for (int i = sections.length - 1; i >= 0; i--) {
                if (sections[i] != null) return i;
            }
            return -1;
        }

        boolean isYSpaceEmpty(int minIdx, int maxIdx) {
            int lo = Math.max(minIdx, 0);
            int hi = Math.min(maxIdx, sections.length - 1);
            for (int i = lo; i <= hi; i++) {
                if (sections[i] != null) return false;
            }
            return true;
        }

        int findNonEmptyCount() {
            int count = 0;
            for (Object section : sections) {
                if (section != null) count++;
            }
            return count;
        }

        long estimateMemory() {
            return 16L * sections.length; // array overhead + refs
        }
    }

    // ---- New impl: array + BitSet tracking + cached range ----
    static class NewImpl {
        final Object[] sections;
        final BitSet nonEmptyMask;
        int highestCached = -1;
        int lowestCached = -1;
        boolean rangeDirty = false;

        NewImpl(int count) {
            sections = new Object[count];
            nonEmptyMask = new BitSet(count);
        }

        void fillSection(int idx) {
            sections[idx] = new Object();
            nonEmptyMask.set(idx);
            rangeDirty = true;
        }

        void clearSection(int idx) {
            sections[idx] = null;
            nonEmptyMask.clear(idx);
            rangeDirty = true;
        }

        private void updateRange() {
            if (!rangeDirty) return;
            highestCached = nonEmptyMask.length() > 0
                ? nonEmptyMask.length() - 1 + (nonEmptyMask.isEmpty() ? -nonEmptyMask.length() : 0)
                : -1;
            if (nonEmptyMask.isEmpty()) {
                highestCached = -1;
                lowestCached = -1;
            } else {
                highestCached = nonEmptyMask.length() - 1;
                while (highestCached >= 0 && !nonEmptyMask.get(highestCached)) highestCached--;
                lowestCached = 0;
                while (lowestCached < nonEmptyMask.length() && !nonEmptyMask.get(lowestCached)) lowestCached++;
                if (lowestCached >= nonEmptyMask.length()) lowestCached = -1;
            }
            rangeDirty = false;
        }

        int getHighestFilled() {
            updateRange();
            return highestCached;
        }

        int getLowestFilled() {
            updateRange();
            return lowestCached;
        }

        boolean isYSpaceEmpty(int minIdx, int maxIdx) {
            if (nonEmptyMask.isEmpty()) return true;
            int lo = Math.max(minIdx, 0);
            int hi = Math.min(maxIdx, sections.length - 1);
            if (lo > hi) return true;
            return nonEmptyMask.nextSetBit(lo) > hi || nonEmptyMask.nextSetBit(lo) < 0;
        }

        int findNonEmptyCount() {
            return nonEmptyMask.cardinality();
        }

        long estimateMemory() {
            return 16L * sections.length + nonEmptyMask.size() / 8L + 16L;
        }
    }

    // ---- Benchmark runner ----
    public static void main(String[] args) {
        System.out.println("=== Chunk Section Performance Benchmark ===\n");

        int[] sectionCounts = {24, 128, 256, 512, 768, 1536, 4096};
        String[] fillPatterns = {"sparse(1)", "terrain(25%)", "all(100%)"};

        for (int count : sectionCounts) {
            for (String pattern : fillPatterns) {
                int filled;
                int[] fillIndices;
                switch (pattern) {
                    case "sparse(1)":
                        filled = 1; fillIndices = new int[]{count / 2}; break;
                    case "terrain(25%)":
                        filled = count / 4;
                        fillIndices = new int[filled];
                        int off = count / 2 - filled / 2;
                        for (int i = 0; i < filled; i++) fillIndices[i] = off + i;
                        break;
                    case "all(100%)":
                    default:
                        filled = count;
                        fillIndices = new int[filled];
                        for (int i = 0; i < filled; i++) fillIndices[i] = i;
                        break;
                }

                OldImpl oldImpl = new OldImpl(count);
                NewImpl newImpl = new NewImpl(count);
                for (int idx : fillIndices) { oldImpl.fillSection(idx); newImpl.fillSection(idx); }

                for (int i = 0; i < 500; i++) {
                    oldImpl.getHighestFilled(); oldImpl.isYSpaceEmpty(0, count - 1);
                    newImpl.getHighestFilled(); newImpl.isYSpaceEmpty(0, count - 1);
                }

                long oldStart = System.nanoTime();
                for (int i = 0; i < 50_000; i++) oldImpl.getHighestFilled();
                long oldH = (System.nanoTime() - oldStart) / 50_000;

                long newStart = System.nanoTime();
                for (int i = 0; i < 50_000; i++) newImpl.getHighestFilled();
                long newH = (System.nanoTime() - newStart) / 50_000;

                oldStart = System.nanoTime();
                for (int i = 0; i < 10_000; i++) oldImpl.isYSpaceEmpty(0, count - 1);
                long oldE = (System.nanoTime() - oldStart) / 10_000;

                newStart = System.nanoTime();
                for (int i = 0; i < 10_000; i++) newImpl.isYSpaceEmpty(0, count - 1);
                long newE = (System.nanoTime() - newStart) / 10_000;

                String speedupH = oldH > 0 ? String.format("%.1fx", (double) oldH / newH) : "N/A";
                String speedupE = oldE > 0 ? String.format("%.1fx", (double) oldE / newE) : "N/A";

                System.out.printf("%-14s sections=%4d filled=%-4d  |  highestFilled: OLD=%6dns NEW=%5dns (%s)  |  isEmpty: OLD=%6dns NEW=%5dns (%s)%n",
                    pattern, count, filled, oldH, newH, speedupH, oldE, newE, speedupE);
            }
            System.out.println();
        }
    }
}
