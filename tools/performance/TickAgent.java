package endless.bench;

import java.io.*;
import java.lang.instrument.*;
import java.lang.management.ManagementFactory;
import java.security.ProtectionDomain;
import jdk.internal.org.objectweb.asm.*;

/** Same measurement hook for the official and Fabric dedicated servers. Never shipped. */
public final class TickAgent {
    private static final com.sun.management.ThreadMXBean THREADS =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    public static void premain(String options, Instrumentation instrumentation) throws Exception {
        instrumentation.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(
            System.getProperty("endless.bench.helper")));
        THREADS.setThreadAllocatedMemoryEnabled(true);
        THREADS.setThreadCpuTimeEnabled(true);
        TickMetrics.initialize(options, () -> THREADS.getCurrentThreadCpuTime(),
            () -> THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()));
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                ProtectionDomain domain, byte[] input) {
                boolean server = name.equals("net/minecraft/server/MinecraftServer");
                boolean rate = name.equals("all") || name.equals("net/minecraft/class_8915") ||
                    name.equals("net/minecraft/server/ServerTickRateManager");
                if (!server && !rate) return null;
                var reader = new ClassReader(input);
                var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                int[] matches = {0};
                reader.accept(new ClassVisitor(Opcodes.ASM8, writer) {
                    @Override public MethodVisitor visitMethod(int access, String method, String desc,
                        String signature, String[] exceptions) {
                        var delegate = super.visitMethod(access, method, desc, signature, exceptions);
                        if (rate && desc.equals("()Z") && (method.equals("d") ||
                            method.equals("method_54679") || method.equals("checkShouldSprintThisTick"))) {
                            matches[0]++;
                            return new MethodVisitor(Opcodes.ASM8, delegate) {
                                @Override public void visitInsn(int opcode) {
                                    if (opcode == Opcodes.IRETURN) {
                                        super.visitInsn(Opcodes.DUP);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "endless/bench/TickMetrics", "sprint", "(Z)V", false);
                                    }
                                    super.visitInsn(opcode);
                                }
                            };
                        }
                        if (!server) return delegate;
                        if (!desc.equals("(Ljava/util/function/BooleanSupplier;)V") ||
                            !(method.equals("tickServer") || method.equals("method_3748") || method.equals("a"))) return delegate;
                        matches[0]++;
                        return new MethodVisitor(Opcodes.ASM8, delegate) {
                            @Override public void visitCode() {
                                super.visitCode();
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "endless/bench/TickMetrics", "enter", "()V", false);
                            }
                            @Override public void visitInsn(int opcode) {
                                if (opcode == Opcodes.RETURN)
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "endless/bench/TickMetrics", "leave", "()V", false);
                                super.visitInsn(opcode);
                            }
                        };
                    }
                }, 0);
                if (matches[0] != 1) throw new IllegalStateException("Expected one benchmark target, got " + matches[0]);
                System.out.println(server ? "ENDLESS_BENCH_TICK_HOOK_PASS" : "ENDLESS_BENCH_SPRINT_HOOK_PASS");
                return writer.toByteArray();
            }
        });
    }

}
