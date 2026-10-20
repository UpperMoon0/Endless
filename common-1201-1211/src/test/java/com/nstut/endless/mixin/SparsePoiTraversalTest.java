package com.nstut.endless.mixin;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

/** Guards the actual storage hooks against the route-unload watchdog regression. */
class SparsePoiTraversalTest {
    @Test void loadedAndNewSparseSectionsMustEnterTheColumnIndex() throws Exception {
        ClassNode type = new ClassNode();
        try (var bytes = SectionStorageMixin.class.getResourceAsStream("SectionStorageMixin.class")) {
            assertNotNull(bytes);
            new ClassReader(bytes).accept(type, 0);
        }
        for (String hook : java.util.List.of("endless$getOrLoad", "endless$loadColumn")) {
            var method = type.methods.stream().filter(m -> m.name.equals(hook)).findFirst().orElseThrow();
            boolean admitted = false;
            int writes = 0;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    if (call.owner.equals("com/nstut/endless/vertical/SparseSectionIndex") && call.name.equals("add")) admitted = true;
                    if (call.owner.contains("Long2ObjectMap") && call.name.equals("put")) {
                        assertTrue(admitted, hook + " must index a section before storing it");
                        writes++;
                    }
                }
            }
            assertTrue(writes > 0, hook + " storage write must be exercised");
        }
    }

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
