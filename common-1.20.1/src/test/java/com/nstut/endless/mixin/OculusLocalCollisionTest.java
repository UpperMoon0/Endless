package com.nstut.endless.mixin;

import java.net.URLClassLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

class OculusLocalCollisionTest {
    @Test void oldLongRedirectOverwritesCallbackAndFailsJvmVerification() throws Exception {
        // Isolate the exact Forge 1.20.1 allocator from Fabric's newer Mixin.
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{
            Path.of(System.getProperty("endless.mixin085.jar")).toUri().toURL()
        }, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("org.spongepowered.asm.")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> result = findLoadedClass(name);
                    if (result == null) result = findClass(name);
                    if (resolve) resolveClass(result);
                    return result;
                }
            }
        }) {
            Class<?> targetClass = loader.loadClass("org.spongepowered.asm.mixin.injection.struct.Target");
            ClassNode owner = new ClassNode(); owner.name = "Collision";
            MethodNode method = new MethodNode(0, "renderLevel", "()V", null, null);
            method.maxLocals = 57;
            Object target = targetClass.getConstructor(ClassNode.class, MethodNode.class).newInstance(owner, method);
            var argMap = targetClass.getMethod("generateArgMap", Type[].class, int.class);
            Type object = Type.getType(Object.class);
            argMap.invoke(target, new Type[]{object}, 0);
            int callback = (int) targetClass.getMethod("allocateLocal").invoke(target);
            argMap.invoke(target, new Type[]{object, object, object}, 0);
            int temporary = ((int[]) argMap.invoke(target, new Type[]{Type.LONG_TYPE}, 0))[0];
            assertEquals(58, callback);
            assertEquals(callback, temporary + 1);
            assertThrows(VerifyError.class, () -> generatedRenderer(callback, temporary, true).getDeclaredMethods());
            // Expression replacement consumes a reference, not a two-slot long.
            assertDoesNotThrow(() -> generatedRenderer(callback, temporary, false).getDeclaredMethod("renderLevel").invoke(null));
        }
    }

    private Class<?> generatedRenderer(int callback, int temporary, boolean oldRedirect) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Collision", null, "java/lang/Object", null);
        var code = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "renderLevel", "()V", null, null);
        code.visitCode();
        code.visitLdcInsn("Oculus callback");
        code.visitVarInsn(Opcodes.ASTORE, callback);
        code.visitInsn(oldRedirect ? Opcodes.LCONST_0 : Opcodes.ACONST_NULL);
        code.visitVarInsn(oldRedirect ? Opcodes.LSTORE : Opcodes.ASTORE, temporary);
        code.visitVarInsn(Opcodes.ALOAD, callback);
        code.visitInsn(Opcodes.POP);
        code.visitInsn(Opcodes.RETURN);
        code.visitMaxs(0, 0);
        code.visitEnd(); writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass("Collision", bytes, 0, bytes.length); }
        }.define();
    }
}
