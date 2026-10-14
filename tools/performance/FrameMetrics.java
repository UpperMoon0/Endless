package endless.bench;

import java.nio.file.*;
import java.io.*;

/** No file I/O in measured frames; phase control is polled by a daemon thread. */
public final class FrameMetrics {
    private static final long[] TIMES = new long[2_000_000];
    private static final int[] PHASES = new int[TIMES.length];
    private static volatile int phase;
    private static volatile int count;
    private static int lastPhase;
    private static long previous;
    private static Path directory;

    public static void start(Path output) throws IOException {
        Files.createDirectories(output);
        directory = output;
        Thread controller = new Thread(() -> {
            while (true) {
                try {
                    phase = Integer.parseInt(Files.readString(output.resolve("phase.txt")).trim());
                    if (phase == -1) { System.exit(0); return; }
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) { return; }
                catch (Exception missing) { phase = 0; }
            }
        }, "frame-benchmark-control");
        controller.setDaemon(true);
        controller.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(output.resolve("frames.csv")))) {
                writer.println("phase,frame_ns");
                for (int i = 0; i < count; i++) writer.println(PHASES[i] + "," + TIMES[i]);
            } catch (IOException failure) { failure.printStackTrace(); }
        }));
    }

    public static void present() {
        long now = System.nanoTime();
        int current = phase;
        if (current >= 1000 && current != lastPhase) capture(current);
        if (current > 0 && current < 1000 && current == lastPhase && previous != 0) {
            if (count == TIMES.length) throw new IllegalStateException("Frame benchmark capacity exceeded");
            TIMES[count] = now - previous;
            PHASES[count++] = current;
        }
        previous = now;
        lastPhase = current;
    }

    private static void capture(int phase) {
        try {
            Class<?> gl = Class.forName("org.lwjgl.opengl.GL11", true, Thread.currentThread().getContextClassLoader());
            String gpu = "vendor=" + gl.getMethod("glGetString", int.class).invoke(null, 0x1F00)
                + "\nrenderer=" + gl.getMethod("glGetString", int.class).invoke(null, 0x1F01)
                + "\nversion=" + gl.getMethod("glGetString", int.class).invoke(null, 0x1F02);
            Files.writeString(directory.resolve("gpu.txt"), gpu);
            int[] viewport = new int[4];
            gl.getMethod("glGetIntegerv", int.class, int[].class).invoke(null, 0x0BA2, viewport);
            int width = viewport[2], height = viewport[3];
            var pixels = java.nio.ByteBuffer.allocateDirect(width * height * 4);
            gl.getMethod("glReadPixels", int.class, int.class, int.class, int.class, int.class, int.class, java.nio.ByteBuffer.class)
                .invoke(null, 0, 0, width, height, 0x1908, 0x1401, pixels);
            var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int offset = ((height - y - 1) * width + x) * 4;
                image.setRGB(x, y, ((pixels.get(offset) & 255) << 16) | ((pixels.get(offset + 1) & 255) << 8) | (pixels.get(offset + 2) & 255));
            }
            javax.imageio.ImageIO.write(image, "png", directory.resolve("scene-" + phase + ".png").toFile());
        } catch (Exception failure) { throw new IllegalStateException("Framebuffer capture failed", failure); }
    }
}
