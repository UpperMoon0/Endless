package endless.bench;

import java.io.*;
import java.util.function.LongSupplier;

/** Bootstrap-visible hook because Mojang's bundler uses a platform-parent classloader. */
public final class TickMetrics {
    private static final long[] TIME = new long[200_000], CPU = new long[200_000],
        BYTES = new long[200_000], WALL = new long[200_000];
    private static int count;
    private static final int[] SPRINT = new int[200_000];
    private static int sprintId;
    private static boolean sprinting;
    private static long begin, cpu, bytes;
    private static LongSupplier cpuCounter, byteCounter;

    public static void initialize(String path, LongSupplier cpus, LongSupplier allocations) {
        cpuCounter = cpus;
        byteCounter = allocations;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try (var out = new PrintWriter(path)) {
                out.println("wall_ms,tick_ns,cpu_ns,allocated_bytes,sprint_id");
                for (int i = 0; i < count; i++)
                    out.println(WALL[i]+","+TIME[i]+","+CPU[i]+","+BYTES[i]+","+SPRINT[i]);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }));
    }

    public static void enter() {
        cpu = cpuCounter.getAsLong();
        bytes = byteCounter.getAsLong();
        begin = System.nanoTime();
    }
    public static void sprint(boolean active) {
        if (active && !sprinting) sprintId++;
        sprinting = active;
    }
    public static void leave() {
        if (count >= TIME.length) throw new IllegalStateException("Benchmark tick capacity exceeded");
        TIME[count] = System.nanoTime() - begin;
        CPU[count] = cpuCounter.getAsLong() - cpu;
        BYTES[count] = byteCounter.getAsLong() - bytes;
        WALL[count] = System.currentTimeMillis();
        SPRINT[count] = sprinting ? sprintId : 0;
        count++;
    }
}
