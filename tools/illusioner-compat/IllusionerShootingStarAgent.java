package dev.lanminecraft.pack;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.jar.JarFile;

public final class IllusionerShootingStarAgent {
    private IllusionerShootingStarAgent() { }

    public static void premain(String argument, Instrumentation instrumentation) throws Exception {
        var location = IllusionerShootingStarAgent.class.getProtectionDomain().getCodeSource().getLocation();
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(Path.of(location.toURI()).toFile()));
        var bridge = Class.forName("dev.lanminecraft.pack.ShootingStarMixinBridge", true, null);
        bridge.getMethod("install", Instrumentation.class).invoke(null, instrumentation);
    }

    public static byte[] patchMixinInfo(byte[] bytecode) {
        return ShootingStarMixinBridge.patchMixinInfo(bytecode);
    }

    public static boolean patchMixinClass(String name, Object node) {
        return ShootingStarMixinBridge.patchMixinClass(name, node);
    }
}
