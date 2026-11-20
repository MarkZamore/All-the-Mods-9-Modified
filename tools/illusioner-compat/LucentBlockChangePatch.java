package dev.lanminecraft.pack;

import java.util.Arrays;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Keep Lucent's physical-client mixin out of integrated ServerLevel writes. */
final class LucentBlockChangePatch {
    static final String TARGET = "com/legacy/lucent/core/mixin/LevelMixin";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String BLOCK_CHANGE =
        "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String HANDLER = "updateLightOnBlockChange";
    private static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static final String MIXIN_INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String MIXIN_AT = "Lorg/spongepowered/asm/mixin/injection/At;";

    static int patch(ClassNode type) {
        require(type.name.equals(TARGET), "Unexpected mixin class");
        var handlers = type.methods.stream().filter(method -> method.name.equals(HANDLER)).toList();
        require(handlers.size() == 1, "Expected one block change handler");
        var method = handlers.get(0);
        require(method.access == Opcodes.ACC_PRIVATE && method.desc.equals(BLOCK_CHANGE + "IIL" + CALLBACK + ";)V")
            && method.tryCatchBlocks.isEmpty(), "Unexpected block change handler signature");
        require(method.visibleAnnotations != null && method.visibleAnnotations.size() == 1, "Unexpected injection count");
        var annotation = method.visibleAnnotations.get(0);
        require(annotation.desc.equals(MIXIN_INJECT) && annotation.values.size() == 4
            && annotation.values.get(0).equals("at") && annotation.values.get(1) instanceof List<?>
            && annotation.values.subList(2, 4).equals(List.of("method", List.of("setBlock" + BLOCK_CHANGE + "II)Z"))),
            "Unexpected setBlock injection");
        var points = (List<?>) annotation.values.get(1);
        require(points.size() == 1 && points.get(0) instanceof AnnotationNode point && point.desc.equals(MIXIN_AT)
            && point.values.equals(List.of("value", "RETURN")), "Unexpected setBlock injection point");
        var code = Arrays.stream(method.instructions.toArray()).filter(instruction -> instruction.getOpcode() >= 0).toList();
        if (code.size() == 13 && call(code.get(2), Opcodes.INVOKEVIRTUAL, LEVEL, "m_5776_", "()Z"))
            return -1;
        require(code.size() == 9 && local(code.get(0), 5)
            && call(code.get(1), Opcodes.INVOKEVIRTUAL, CALLBACK, "getReturnValue", "()Ljava/lang/Object;")
            && code.get(2) instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
            && cast.desc.equals("java/lang/Boolean")
            && call(code.get(3), Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z")
            && code.get(4) instanceof JumpInsnNode
            && local(code.get(5), 1) && local(code.get(6), 2)
            && call(code.get(7), Opcodes.INVOKESTATIC, "com/legacy/lucent/core/dynamic_lighting/DynamicLightingEngine",
                "blockChanged", BLOCK_CHANGE + ")V")
            && code.get(8).getOpcode() == Opcodes.RETURN, "Unexpected block change handler instructions");
        var stop = (JumpInsnNode) code.get(4);
        AbstractInsnNode destination = stop.label;
        while (destination != null && destination.getOpcode() < 0) destination = destination.getNext();
        require(stop.getOpcode() == Opcodes.IFEQ && destination == code.get(8), "Unexpected return branch");

        var guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new TypeInsnNode(Opcodes.CHECKCAST, LEVEL));
        guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LEVEL, "m_5776_", "()Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, stop.label));
        method.instructions.insertBefore(code.get(0), guard);
        return type.methods.indexOf(method);
    }

    private static boolean local(AbstractInsnNode instruction, int slot) {
        return instruction instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == slot;
    }

    private static boolean call(AbstractInsnNode instruction, int opcode, String owner, String name, String descriptor) {
        return instruction instanceof MethodInsnNode method && method.getOpcode() == opcode
            && method.owner.equals(owner) && method.name.equals(name) && method.desc.equals(descriptor) && !method.itf;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException("[ATM9 Modified Lucent compatibility] " + reason);
    }

    private LucentBlockChangePatch() { }
}
