package com.nstut.endless.benchmark;

import org.junit.jupiter.api.Test;

public class ChunkBenchmarkTest {

    @Test
    public void runBenchmark() {
        System.out.println();
        System.out.println("=== Endless Chunk Section Performance Benchmark ===");
        System.out.println("Compares OLD (scan all sections) vs NEW (bitset + cached range)");
        System.out.println("Each row: 50k iterations for highestFilled, 10k for isEmpty");
        System.out.println();

        ChunkBenchmark.main(new String[0]);
    }
}
