package com.nstut.endless.neoforge.compat;
public final class EmbeddiumFrameClock {
    private static long frame;
    private static boolean previewAttempted;
    public static Runnable afterFrame;
    private EmbeddiumFrameClock() {}
    public static long frame() { return frame; }
    public static void beginFrame() {
        frame++;
        if (!previewAttempted && Boolean.getBoolean("endless.shaderPreview")) {
            previewAttempted = true;
            try { Class.forName("com.nstut.endless.testing.renderer.EmbeddiumShaderPreview").getMethod("arm").invoke(null); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("Renderer fixture missing", e); }
        }
    }
    public static void endFrame() { if (afterFrame != null) afterFrame.run(); }
}
