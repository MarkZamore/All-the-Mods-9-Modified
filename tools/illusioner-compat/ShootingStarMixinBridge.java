package dev.lanminecraft.pack;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Pack-only compatibility for the two respawn screen handlers. */
public final class ShootingStarMixinBridge implements ClassFileTransformer {
    private static final String MIXIN_INFO = "org/spongepowered/asm/mixin/transformer/MixinInfo";
    private static final String MIXIN_INFO_SHA = "6affb8b0409394aa787ac04133051ec542d2a3bf79f2e455b71a8d6ad107fdf5";
    private static final String TARGET = "cyou/rimuru/shootingstardemo/mc1201/client/mixin/ClientPacketListenerMixin";
    private static final String BRIDGE = "dev/lanminecraft/pack/ShootingStarMixinBridge";
    private static final String HANDLER = "shooting_star_demo$noLoadingScreen";
    private static final String CLIENT = "net/minecraft/client/Minecraft";
    private static final String SCREEN = "Lnet/minecraft/client/gui/screens/Screen;";
    private static final String SET_SCREEN = "m_91152_";
    private static final String FILM_STATE = "cyou/rimuru/shootingstardemo/mc1201/lll1ili11";
    private static final String FILM_ACTIVE = "liiiIll";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String PREFIX = "[ATM9 Modified Illusioner compatibility] ";

    private final Instrumentation instrumentation;

    private ShootingStarMixinBridge(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    public static void install(Instrumentation instrumentation) {
        instrumentation.addTransformer(new ShootingStarMixinBridge(instrumentation));
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String name, Class<?> previous,
                            ProtectionDomain protectionDomain, byte[] bytes) {
        if (!MIXIN_INFO.equals(name)) return null;
        try {
            byte[] result = patchMixinInfo(bytes);
            if (module.isNamed()) {
                instrumentation.redefineModule(module, Set.of(ShootingStarMixinBridge.class.getModule()),
                    Map.of(), Map.of(), Set.of(), Map.of());
            }
            return result;
        } catch (RuntimeException failure) {
            System.err.println(PREFIX + "MixinInfo validation failed: " + failure);
            return null;
        }
    }

    public static byte[] patchMixinInfo(byte[] bytes) {
        require(hash(bytes).equals(MIXIN_INFO_SHA), "Unexpected MixinInfo bytecode");
        var type = new ClassNode();
        new ClassReader(bytes).accept(type, 0);
        require(type.name.equals(MIXIN_INFO), "Unexpected MixinInfo class name");
        String nodeDescriptor = "L" + "org.objectweb.asm.tree.ClassNode".replace('.', '/') + ";";
        var loader = uniqueMethod(type, "loadMixinClass", "(Ljava/lang/String;)" + nodeDescriptor);
        var returns = new ArrayList<AbstractInsnNode>();
        for (var instruction : loader.instructions) {
            if (instruction.getOpcode() == Opcodes.ARETURN) returns.add(instruction);
        }
        require(returns.size() == 1, "Expected one ClassNode return");
        var invoke = new InsnList();
        invoke.add(new InsnNode(Opcodes.DUP));
        invoke.add(new VarInsnNode(Opcodes.ALOAD, 1));
        invoke.add(new InsnNode(Opcodes.SWAP));
        invoke.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "patchMixinClass",
            "(Ljava/lang/String;Ljava/lang/Object;)Z", false));
        invoke.add(new InsnNode(Opcodes.POP));
        loader.instructions.insertBefore(returns.get(0), invoke);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        return writer.toByteArray();
    }

    public static boolean patchMixinClass(String name, Object node) {
        if (name == null || !TARGET.equals(name.replace('.', '/'))) return false;
        try {
            // The real ClassNode and its visitor live in Forge's ASM module. Only
            // the reviewed MethodNode crosses back, using that same ASM identity.
            var classLoader = node.getClass().getClassLoader();
            var foreignNode = Class.forName("org.objectweb.asm.tree.ClassNode", false, classLoader);
            var foreignVisitor = Class.forName("org.objectweb.asm.ClassVisitor", false, classLoader);
            var writerType = Class.forName("org.objectweb.asm.ClassWriter", false, classLoader);
            var writer = writerType.getConstructor(int.class).newInstance(0);
            foreignNode.getMethod("accept", foreignVisitor).invoke(node, writer);
            var original = (byte[]) writerType.getMethod("toByteArray").invoke(writer);
            var type = new ClassNode();
            new ClassReader(original).accept(type, 0);
            int index = patchHandler(type);
            if (index < 0) return false;
            var output = new ClassWriter(0);
            type.accept(output);
            var readerType = Class.forName("org.objectweb.asm.ClassReader", false, classLoader);
            var reader = readerType.getConstructor(byte[].class).newInstance(output.toByteArray());
            var replacementNode = foreignNode.getConstructor().newInstance();
            readerType.getMethod("accept", foreignVisitor, int.class).invoke(reader, replacementNode, 0);
            var methods = foreignNode.getField("methods");
            replaceMethod(methods.get(node), methods.get(replacementNode), index);
            System.out.println(PREFIX + "Composed the Shooting Star and Illusioner respawn screen handlers");
            return true;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(PREFIX + "Cannot transfer the measured mixin method", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static void replaceMethod(Object original, Object replacement, int index) {
        ((List<Object>) original).set(index, ((List<?>) replacement).get(index));
    }

    private static int patchHandler(ClassNode type) {
        require(type.name.equals(TARGET), "Unexpected Shooting Star mixin class name");
        var handlers = type.methods.stream().filter(method -> method.name.equals(HANDLER)).toList();
        require(handlers.size() == 1, "Expected a unique Shooting Star respawn handler");
        var handler = handlers.get(0);
        if (handler.desc.equals("(" + SCREEN + ")" + SCREEN) && onlyAnnotation(handler, MODIFY_ARG)) return -1;
        require(handler.access == Opcodes.ACC_PRIVATE && handler.desc.equals("(L" + CLIENT + ";" + SCREEN + ")V")
            && onlyAnnotation(handler, REDIRECT), "Unexpected Shooting Star respawn handler signature");
        var annotation = handler.visibleAnnotations.get(0);
        require(annotation.values.size() == 4 && annotation.values.subList(0, 3).equals(List.of("method", List.of("m_7992_"), "at"))
            && annotation.values.get(3) instanceof AnnotationNode, "Unexpected Shooting Star Redirect selectors");
        var injectionPoint = (AnnotationNode) annotation.values.get(3);
        require(injectionPoint.desc.equals(AT) && injectionPoint.values.equals(List.of("value", "INVOKE", "target",
            "L" + CLIENT + ";" + SET_SCREEN + "(" + SCREEN + ")V", "ordinal", 0)), "Unexpected Minecraft.setScreen selector");
        var code = new ArrayList<AbstractInsnNode>();
        for (var instruction : handler.instructions) {
            if (instruction.getOpcode() >= 0) code.add(instruction);
        }
        require(code.size() == 8 && local(code.get(0), 1) && call(code.get(1), Opcodes.INVOKESTATIC, FILM_STATE, FILM_ACTIVE, "()Z")
            && branch(code.get(2), Opcodes.IFEQ, code.get(5)) && code.get(3).getOpcode() == Opcodes.ACONST_NULL
            && branch(code.get(4), Opcodes.GOTO, code.get(6)) && local(code.get(5), 2)
            && call(code.get(6), Opcodes.INVOKEVIRTUAL, CLIENT, SET_SCREEN, "(" + SCREEN + ")V")
            && code.get(7).getOpcode() == Opcodes.RETURN && handler.tryCatchBlocks.isEmpty(), "Unexpected Shooting Star predicate bytecode");

        // Mixin identifies the unique Screen argument before or after Illusioner's Redirect replaces the call.
        annotation.desc = MODIFY_ARG;
        annotation.values.add("require");
        annotation.values.add(1);
        handler.desc = "(" + SCREEN + ")" + SCREEN;
        handler.instructions.clear();
        handler.localVariables = null;
        var keepScreen = new LabelNode();
        handler.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FILM_STATE, FILM_ACTIVE, "()Z", false));
        handler.instructions.add(new JumpInsnNode(Opcodes.IFEQ, keepScreen));
        handler.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        handler.instructions.add(new InsnNode(Opcodes.ARETURN));
        handler.instructions.add(keepScreen);
        handler.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        handler.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        handler.instructions.add(new InsnNode(Opcodes.ARETURN));
        handler.maxLocals = 2;
        handler.maxStack = 1;
        return type.methods.indexOf(handler);
    }

    private static boolean onlyAnnotation(MethodNode method, String descriptor) {
        return method.visibleAnnotations != null && method.visibleAnnotations.size() == 1
            && method.visibleAnnotations.get(0).desc.equals(descriptor);
    }

    private static MethodNode uniqueMethod(ClassNode type, String name, String descriptor) {
        var matches = type.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor)).toList();
        require(matches.size() == 1, "Unexpected " + type.name + "." + name + descriptor);
        return matches.get(0);
    }

    private static boolean local(AbstractInsnNode instruction, int index) {
        return instruction instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == index;
    }

    private static boolean call(AbstractInsnNode instruction, int opcode, String owner, String name, String descriptor) {
        return instruction instanceof MethodInsnNode method && method.getOpcode() == opcode && method.owner.equals(owner)
            && method.name.equals(name) && method.desc.equals(descriptor) && !method.itf;
    }

    private static boolean branch(AbstractInsnNode instruction, int opcode, AbstractInsnNode destination) {
        if (!(instruction instanceof JumpInsnNode jump) || jump.getOpcode() != opcode) return false;
        AbstractInsnNode target = jump.label;
        while (target != null && target.getOpcode() < 0) target = target.getNext();
        return target == destination;
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(PREFIX + reason);
    }
}
