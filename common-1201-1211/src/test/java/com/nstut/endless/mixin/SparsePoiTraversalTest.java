package com.nstut.endless.mixin;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

/** Guards the actual storage hooks against the route-unload watchdog regression. */
class SparsePoiTraversalTest {
    @Test void poiReadSaveAndUnloadCannotScanVanillasGlobalSectionMap() throws Exception {
        ClassNode type = new ClassNode();
        try (var bytes = SectionStorageMixin.class.getResourceAsStream("SectionStorageMixin.class")) {
            assertNotNull(bytes);
            new ClassReader(bytes).accept(type, 0);
        }
        var hooks = Map.of("endless$getExtendedSections", "sections",
            "endless$saveColumn", "sections", "endless$unloadExtendedColumn", "remove");
        for (var hook : hooks.entrySet()) {
            var method = type.methods.stream().filter(m -> m.name.equals(hook.getKey())).findFirst().orElseThrow();
            boolean indexed = false;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    assertNotEquals("long2ObjectEntrySet", call.name,
                        hook.getKey() + " must not traverse unrelated dense POI sections");
                    indexed |= call.owner.equals("com/nstut/endless/vertical/SparseSectionIndex")
                        && call.name.equals(hook.getValue());
                }
            }
            assertTrue(indexed, hook.getKey() + " must use its owning-column index");
        }
    }
}
