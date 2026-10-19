package com.nstut.endless.vertical;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShaderVerticalBlendTest {
    private static final String SOURCE = "#ifdef DH_BLENDING\n"
        + "float fog = max(length(playerPos.xz), abs(playerPos.y)) / far;\n"
        + "if (newDither > fog) discard;\n#endif\n";
    @Test void expandedViewPreservesHorizontalSeamAndUnrelatedShaderCode() {
        String adapted = ShaderVerticalBlend.adapt(SOURCE, 256);
        assertEquals("#ifdef DH_BLENDING\nfloat fog = length(playerPos.xz) / far;\n"
            + "if (newDither > fog) discard;\n#endif\n", adapted);
        assertFalse(adapted.contains("abs(playerPos.y)"));
        assertSame(SOURCE, ShaderVerticalBlend.adapt(SOURCE, 32));
        String unrelated = SOURCE.replace("DH_BLENDING", "SOME_OTHER_BLEND");
        assertSame(unrelated, ShaderVerticalBlend.adapt(unrelated, 256));
        String differentPack = "#ifdef DH_BLENDING\nfloat fog = length(viewPos) / far;\n#endif";
        assertEquals(differentPack, ShaderVerticalBlend.adapt(differentPack, 256));
    }
}
