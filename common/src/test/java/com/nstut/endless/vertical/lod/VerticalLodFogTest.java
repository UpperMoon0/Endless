package com.nstut.endless.vertical.lod;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerticalLodFogTest {
    @Test void legacyAndSodiumCylindersKeepTheirOriginalSphereFallback() {
        for(String function:new String[]{"float fog_distance(mat4 transform, vec3 point, int shape) { return length(point); }",
            "float getFragDistance(int mode, vec3 coordinate) { return length(coordinate); }"}) {
            String patched=VerticalLodFog.adapt(function);
            assertTrue(patched.contains("uniform int endlessVerticalLod;"));
            assertTrue(patched.contains(" == 1) return length("));
            assertTrue(patched.contains("return length("));
            assertSame(patched,VerticalLodFog.adapt(patched));
        }
    }
    @Test void modernFogKeepsEnvironmentalSphericalDistanceAndVersionOrder() {
        String source="#version 330\n#extension GL_ARB_explicit_attrib_location : enable\n\n"
            +"float fog_spherical_distance(vec3 point) { return length(point); }\n"
            +"float fog_cylindrical_distance(vec3 point) { return max(length(point.xz),abs(point.y)); }\n";
        String patched=VerticalLodFog.adapt(source);
        assertTrue(patched.startsWith("#version 330\n#extension"));
        assertTrue(patched.indexOf("uniform int")>patched.indexOf("#extension"));
        assertTrue(patched.contains("float fog_spherical_distance(vec3 point) { return length(point); }"));
        assertTrue(patched.contains("if (endlessVerticalLod != 0) return length(point.xz);"));
        assertTrue(patched.contains("return max(length(point.xz),abs(point.y));"));
    }
    @Test void unknownShaderFunctionsAreUntouched() {
        String source="float anotherFog(vec3 point) { return length(point); }";
        assertSame(source,VerticalLodFog.adapt(source));
    }
    @Test void modernSodiumRetainsTheIndependentEnvironmentalDistance() {
        String source="vec2 getFragDistance(vec3 point) { return vec2(max(length(point.xz),abs(point.y)),length(point)); }";
        String patched=VerticalLodFog.adapt(source);
        assertTrue(patched.contains("return vec2(length(point.xz),length(point));"));
        assertTrue(patched.contains("return vec2(max(length(point.xz),abs(point.y)),length(point));"));
    }
}
