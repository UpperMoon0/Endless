package endless.bench;

import java.lang.instrument.*;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;
import jdk.internal.org.objectweb.asm.*;

/** Test-only hook at the actual GLFW present boundary, independent of mappings. */
public final class FrameAgent {
    public static void premain(String output, Instrumentation instrumentation) throws Exception {
        Path jar = Path.of(FrameAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(jar.getParent().resolve("frame-helper.jar").toFile()));
        FrameMetrics.start(Path.of(output));
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] input) {
                if (!"com/mojang/blaze3d/systems/RenderSystem".equals(name)) return null;
                ClassReader reader = new ClassReader(input);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                int[] matches = {0};
                reader.accept(new ClassVisitor(Opcodes.ASM8, writer) {
                    @Override public MethodVisitor visitMethod(int access, String method, String desc,
                            String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM8, super.visitMethod(access, method, desc, signature, exceptions)) {
                            @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                                if (owner.equals("org/lwjgl/glfw/GLFW") && method.equals("glfwSwapBuffers") && desc.equals("(J)V")) {
                                    matches[0]++;
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "endless/bench/FrameMetrics", "present", "()V", false);
                                }
                                super.visitMethodInsn(opcode, owner, method, desc, itf);
                            }
                        };
                    }
                }, 0);
                if (matches[0] == 0) return null;
                System.out.println("ENDLESS_FRAME_HOOK " + name + " " + matches[0]);
                return writer.toByteArray();
            }
        });
    }
}
