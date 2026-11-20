package dev.lanminecraft.pack;

import java.lang.reflect.Method;
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
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Runs the exact handlers in bounded local fixtures without starting Minecraft. */
public final class IllusionerShootingStarProbe {
    private static final String DEMO = "cyou/rimuru/shootingstardemo/mc1201/client/mixin/ClientPacketListenerMixin";
    private static final String ILLUSIONER = "com/fqkeh/illusioner/client/mixin/ClientPacketListenerLevelLoadingMixin";
    private static final String PACKET_LISTENER = "net/minecraft/client/multiplayer/ClientPacketListener";
    private static final String MIXIN_INFO = "org/spongepowered/asm/mixin/transformer/MixinInfo";
    private static final String DEMO_HANDLER = "shooting_star_demo$noLoadingScreen";
    private static final String ILLUSIONER_HANDLER = "illusioner$keepArenaVisible";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String SET_SCREEN = "(L" + SCREEN + ";)V";
    private static final String ANNOTATION = "Lorg/spongepowered/asm/mixin/injection/";

    public static void main(String[] args) throws Exception {
        require(args.length == 4, "Expected Shooting Star JAR, Illusioner JAR, patched Forge client JAR and Mixin JAR");
        ClassNode original = read(Path.of(args[0]), DEMO);
        ClassNode patched = read(Path.of(args[0]), DEMO);
        ClassNode illusioner = read(Path.of(args[1]), ILLUSIONER);
        require(!IllusionerShootingStarAgent.patchMixinClass(ILLUSIONER.replace('/', '.'), illusioner),
            "Illusioner mixin unexpectedly changed");
        require(IllusionerShootingStarAgent.patchMixinClass(DEMO.replace('/', '.'), patched),
            "Exact Shooting Star mixin did not match");

        MethodNode previous = method(original, DEMO_HANDLER);
        MethodNode replacement = method(patched, DEMO_HANDLER);
        AnnotationNode redirect = annotation(previous, "Redirect");
        AnnotationNode modify = annotation(replacement, "ModifyArg");
        annotation(method(illusioner, ILLUSIONER_HANDLER), "Redirect");
        require(value(modify, "method").equals(value(redirect, "method")),
            "The selected packet handler changed");
        AnnotationNode before = (AnnotationNode) value(redirect, "at");
        AnnotationNode after = (AnnotationNode) value(modify, "at");
        require(before.values.equals(after.values), "The selected callsite changed");
        Object index = value(modify, "index");
        require(index == null || index.equals(-1), "Screen must be selected by unique type in either injection order");
        require(replacement.desc.equals("(L" + SCREEN + ";)L" + SCREEN + ";"), "Wrong ModifyArg handler descriptor");
        ClassNode originalRest = read(Path.of(args[0]), DEMO);
        ClassNode patchedRest = copy(patched);
        originalRest.methods.remove(method(originalRest, DEMO_HANDLER));
        patchedRest.methods.remove(method(patchedRest, DEMO_HANDLER));
        require(Arrays.equals(bytes(originalRest), bytes(patchedRest)), "Other Shooting Star hooks changed");

        MethodNode respawn = method(read(Path.of(args[2]), PACKET_LISTENER), "m_7992_");
        long calls = Arrays.stream(respawn.instructions.toArray())
            .filter(i -> i instanceof MethodInsnNode m && m.owner.equals(MINECRAFT)
                && m.name.equals("m_91152_") && m.desc.equals(SET_SCREEN))
            .count();
        require(calls == 2 && value(after, "ordinal").equals(0), "Unexpected Forge respawn setScreen calls");
        byte[] selectedMixinInfo = classBytes(Path.of(args[3]), MIXIN_INFO);
        byte[] instrumented = IllusionerShootingStarAgent.patchMixinInfo(selectedMixinInfo);
        require(instrumented != null && !Arrays.equals(selectedMixinInfo, instrumented)
            && new ClassReader(instrumented).getClassName().equals(MIXIN_INFO), "Exact Mixin loader hook did not apply");

        runBehavior(original, patched, illusioner);
        System.out.println("PASS exact Forge respawn site and Mixin load hook, preserved other handlers, four film/encounter combinations");
    }

    private static void runBehavior(ClassNode original, ClassNode patched, ClassNode illusioner) throws Exception {
        Class<?> oldDemo = fixture(original, DEMO_HANDLER);
        Class<?> newDemo = fixture(patched, DEMO_HANDLER);
        Class<?> boss = fixture(illusioner, ILLUSIONER_HANDLER);
        Object originalMixin = newInstance(oldDemo);
        Object patchedMixin = newInstance(newDemo);
        Object bossMixin = newInstance(boss);
        Method oldHandler = callable(oldDemo, DEMO_HANDLER, Minecraft.class, Screen.class);
        Method newHandler = callable(newDemo, DEMO_HANDLER, Screen.class);
        Method bossHandler = callable(boss, ILLUSIONER_HANDLER, Minecraft.class, Screen.class);

        for (boolean film : new boolean[] {false, true}) {
            for (boolean encounter : new boolean[] {false, true}) {
                Film.active = film;
                Encounter.active = encounter;
                Minecraft minecraft = new Minecraft();
                Screen next = new Screen();
                oldHandler.invoke(originalMixin, minecraft, next);
                Object candidate = newHandler.invoke(patchedMixin, next);
                require(minecraft.writes == 1 && candidate == minecraft.screen
                    && candidate == (film ? null : next), "Shooting Star film decision changed");
                Screen previous = new Screen();
                minecraft.screen = previous;
                minecraft.writes = 0;
                bossHandler.invoke(bossMixin, minecraft, candidate);
                require(minecraft.writes == (encounter ? 0 : 1)
                    && minecraft.screen == (encounter ? previous : candidate), "Composition changed the selected screen");
            }
        }
    }

    private static Class<?> fixture(ClassNode source, String handler) {
        ClassNode isolated = copy(source);
        isolated.access &= ~Opcodes.ACC_ABSTRACT;
        isolated.methods.removeIf(m -> !m.name.equals("<init>") && !m.name.equals(handler));
        Map<String, String> types = Map.of(
            MINECRAFT, Type.getInternalName(Minecraft.class),
            SCREEN, Type.getInternalName(Screen.class),
            "cyou/rimuru/shootingstardemo/mc1201/lll1ili11", Type.getInternalName(Film.class),
            "com/fqkeh/illusioner/client/encounter/ClientEncounterController", Type.getInternalName(Encounter.class));
        var writer = new ClassWriter(0);
        isolated.accept(new ClassRemapper(writer, new SimpleRemapper(Opcodes.ASM9, types)));
        return new FixtureLoader().define(writer.toByteArray());
    }

    private static Object newInstance(Class<?> owner) throws Exception {
        var constructor = owner.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Method callable(Class<?> owner, String name, Class<?>... arguments) throws Exception {
        Method target = owner.getDeclaredMethod(name, arguments);
        target.setAccessible(true);
        return target;
    }

    private static ClassNode copy(ClassNode original) {
        var copy = new ClassNode();
        original.accept(copy);
        return copy;
    }

    private static ClassNode read(Path jar, String name) throws Exception {
        var result = new ClassNode();
        new ClassReader(classBytes(jar, name)).accept(result, 0);
        return result;
    }

    private static byte[] classBytes(Path jar, String name) throws Exception {
        try (var archive = new ZipFile(jar.toFile());
             var input = archive.getInputStream(archive.getEntry(name + ".class"))) {
            return input.readAllBytes();
        }
    }

    private static byte[] bytes(ClassNode node) {
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode method(ClassNode type, String name) {
        return type.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static AnnotationNode annotation(MethodNode method, String name) {
        return method.visibleAnnotations.stream().filter(a -> a.desc.equals(ANNOTATION + name + ";"))
            .findFirst().orElseThrow();
    }

    private static Object value(AnnotationNode annotation, String name) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(name)) return annotation.values.get(i + 1);
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class Screen { }

    public static final class Minecraft {
        private int writes;
        private Screen screen;
        public void m_91152_(Screen next) {
            ++writes;
            screen = next;
        }
    }

    public static final class Film {
        private static boolean active;
        public static boolean liiiIll() { return active; }
    }

    public static final class Encounter {
        private static boolean active;
        public static Encounter get() { return new Encounter(); }
        public boolean active() { return active; }
    }

    private static final class FixtureLoader extends ClassLoader {
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
}
