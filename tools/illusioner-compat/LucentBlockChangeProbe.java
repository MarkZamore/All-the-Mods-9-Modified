package dev.lanminecraft.pack;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Check exact Forge packet entry points and execute the patched Lucent callback. */
public final class LucentBlockChangeProbe {
    private static final String MIXIN = "com/legacy/lucent/core/mixin/LevelMixin";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String ENGINE = "com/legacy/lucent/core/dynamic_lighting/DynamicLightingEngine";
    private static final String CLIENT_LEVEL = "net/minecraft/client/multiplayer/ClientLevel";
    private static final String POS = "net/minecraft/core/BlockPos";
    private static final String STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static final String CHANGE_ARGS = "(L" + POS + ";L" + STATE + ";";
    private static final String HANDLER = "updateLightOnBlockChange";

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "Expected Lucent JAR and patched Forge client JAR");
        verifyPackets(Path.of(args[1]));
        ClassNode fixture = read(Path.of(args[0]), MIXIN);
        require(IllusionerShootingStarAgent.patchMixinClass(MIXIN.replace('/', '.'), fixture),
            "Exact Lucent handler did not match");
        require(!IllusionerShootingStarAgent.patchMixinClass(MIXIN.replace('/', '.'), fixture),
            "Guard applied twice");
        verifyCallback(fixture);
        System.out.println("PASS Forge block-update routes and Lucent logical client/server callback cases");
    }

    private static void verifyPackets(Path forge) throws Exception {
        var listener = read(forge, "net/minecraft/client/multiplayer/ClientPacketListener");
        var single = method(listener, "m_6773_", "(Lnet/minecraft/network/protocol/game/ClientboundBlockUpdatePacket;)V");
        require(calls(single, "net/minecraft/network/protocol/PacketUtils", "m_131363_", null),
            "Single-block packet lost its client-thread guard");
        require(calls(single, CLIENT_LEVEL, "m_233653_", CHANGE_ARGS + "I)V"), "Single-block packet route changed");
        var batch = method(listener, "m_5771_", "(Lnet/minecraft/network/protocol/game/ClientboundSectionBlocksUpdatePacket;)V");
        require(calls(batch, "net/minecraft/network/protocol/PacketUtils", "m_131363_", null),
            "Batch-block packet lost its client-thread guard");
        require(calls(batch, "net/minecraft/network/protocol/game/ClientboundSectionBlocksUpdatePacket",
            "m_132992_", "(Ljava/util/function/BiConsumer;)V"), "Batch packet enumeration changed");
        require(listener.methods.stream().anyMatch(m -> m.desc.equals(CHANGE_ARGS + ")V")
            && calls(m, CLIENT_LEVEL, "m_233653_", CHANGE_ARGS + "I)V")), "Batch block consumer route changed");
        var clientLevel = read(forge, CLIENT_LEVEL);
        var apply = method(clientLevel, "m_233653_", CHANGE_ARGS + "I)V");
        require(calls(apply, LEVEL, "m_6933_", CHANGE_ARGS + "II)Z"), "Replicated block did not reach Level.setBlock");
        var level = read(forge, LEVEL);
        var side = method(level, "m_5776_", "()Z");
        require(Arrays.stream(side.instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode field
            && field.getOpcode() == Opcodes.GETFIELD && field.name.equals("f_46443_") && field.desc.equals("Z")),
            "Expected native Level.isClientSide accessor");
    }

    private static void verifyCallback(ClassNode fixture) throws Exception {
        // Mimic Mixin's merge by giving the unchanged callback a receiver with the target Level API.
        fixture.superName = LEVEL;
        var constructor = method(fixture, "<init>", "()V");
        for (var instruction : constructor.instructions) {
            if (instruction instanceof MethodInsnNode invoke
                && invoke.owner.equals("java/lang/Object") && invoke.name.equals("<init>"))
                invoke.owner = LEVEL;
        }
        var types = Map.of(
            LEVEL, Type.getInternalName(Level.class), ENGINE, Type.getInternalName(Engine.class),
            POS, Type.getInternalName(BlockPos.class), STATE, Type.getInternalName(BlockState.class),
            CALLBACK, Type.getInternalName(Callback.class));
        var writer = new ClassWriter(0);
        fixture.accept(new ClassRemapper(writer, new SimpleRemapper(Opcodes.ASM9, types)));
        Class<?> test = new FixtureLoader().define(writer.toByteArray());
        var callback = test.getDeclaredMethod(HANDLER, BlockPos.class, BlockState.class, int.class, int.class, Callback.class);
        callback.setAccessible(true);
        Level instance = (Level) test.getConstructor().newInstance();
        var pos = new BlockPos();
        var state = new BlockState();

        for (boolean client : new boolean[] {false, true}) {
            for (boolean success : new boolean[] {false, true}) {
                instance.client = client;
                Engine.invocations = 0;
                Engine.pos = null;
                Engine.state = null;
                callback.invoke(instance, pos, state, 0, 0, new Callback(success));
                if (client && success) {
                    require(Engine.invocations == 1 && Engine.pos == pos && Engine.state == state,
                        "Successful client mutation did not retain original lighting call");
                } else {
                    require(Engine.invocations == 0, "Server-side or failed mutation reached client lighting");
                }
            }
        }
    }

    private static ClassNode read(Path jar, String name) throws Exception {
        try (var archive = new ZipFile(jar.toFile());
             var input = archive.getInputStream(archive.getEntry(name + ".class"))) {
            var type = new ClassNode();
            new ClassReader(input).accept(type, 0);
            return type;
        }
    }

    private static MethodNode method(ClassNode type, String name, String descriptor) {
        return type.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst().orElseThrow();
    }

    private static boolean calls(MethodNode method, String owner, String name, String descriptor) {
        return Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals(owner)
            && call.name.equals(name) && (descriptor == null || call.desc.equals(descriptor)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static class Level {
        private boolean client;
        public boolean m_5776_() { return client; }
    }

    public static final class BlockPos { }
    public static final class BlockState { }

    public record Callback(boolean success) {
        public Object getReturnValue() { return success; }
    }

    public static final class Engine {
        private static int invocations;
        private static BlockPos pos;
        private static BlockState state;
        public static void blockChanged(BlockPos position, BlockState blockState) {
            ++invocations;
            pos = position;
            state = blockState;
        }
    }

    private static final class FixtureLoader extends ClassLoader {
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
}
