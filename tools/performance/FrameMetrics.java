package endless.bench;

import java.nio.file.*;
import java.io.*;

/** No file I/O in measured frames; phase control is polled by a daemon thread. */
public final class FrameMetrics {
    private static final long[] TIMES = new long[2_000_000];
    private static final int[] PHASES = new int[TIMES.length];
    private static volatile int phase;
    private static volatile int observedPhase = Integer.MIN_VALUE;
    private static volatile int count;
    private static int lastPhase;
    private static long previous;
    private static Path directory;

    public static void start(Path output) throws IOException {
        Files.createDirectories(output);
        directory = output;
        Thread controller = new Thread(() -> {
            int acknowledged = Integer.MIN_VALUE;
            while (true) {
                try {
                    phase = Integer.parseInt(Files.readString(output.resolve("phase.txt")).trim());
                    if (phase == -1) { System.exit(0); return; }
                    int observed = observedPhase;
                    if (observed != acknowledged) {
                        Files.writeString(output.resolve("phase-ack.txt"), Integer.toString(observed));
                        acknowledged = observed;
                    }
                } catch (Exception missing) { phase = 0; }
                try { Thread.sleep(50); }
                catch (InterruptedException interrupted) { return; }
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
        if (lastPhase > 0 && lastPhase < 1000 && (current == lastPhase || current == 0) && previous != 0) {
            if (count == TIMES.length) throw new IllegalStateException("Frame benchmark capacity exceeded");
            TIMES[count] = now - previous;
            PHASES[count++] = lastPhase;
        }
        previous = now;
        lastPhase = current;
        observedPhase = current;
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
            int neutralPixels = 0, redPixels = 0;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int offset = ((height - y - 1) * width + x) * 4;
                int red = pixels.get(offset) & 255, green = pixels.get(offset + 1) & 255, blue = pixels.get(offset + 2) & 255;
                if (red > 20 && red < 220 && Math.abs(red - green) <= 10 && Math.abs(green - blue) <= 10) neutralPixels++;
                if (red > 80 && red > green * 1.3 && red > blue * 1.3) redPixels++;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
            javax.imageio.ImageIO.write(image, "png", directory.resolve("scene-" + phase + ".png").toFile());
            Files.writeString(directory.resolve("scene-" + phase + ".txt"), "width=" + width + "\nheight=" + height + "\nneutralPixels=" + neutralPixels + "\nredPixels=" + redPixels);
        } catch (Exception failure) { throw new IllegalStateException("Framebuffer capture failed", failure); }
    }
}
