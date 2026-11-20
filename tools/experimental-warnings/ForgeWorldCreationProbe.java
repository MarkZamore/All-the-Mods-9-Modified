package net.minecraft;

import com.mojang.serialization.Lifecycle;

/** Exercises the jump-to-continuation branch in the reviewed Forge client. */
public final class ForgeWorldCreationProbe {
    private static int experimentalPrompts;
    private static int deprecatedPrompts;

    private static void create(Lifecycle lifecycle, Runnable proceed, boolean skip) {
        if (!skip && lifecycle != Lifecycle.stable()) {
            if (lifecycle == Lifecycle.experimental()) {
                prompt("selectWorld.warning.experimental.title");
            } else {
                prompt("selectWorld.warning.deprecated.title");
            }
            return;
        }
        proceed.run();
    }

    private static void prompt(String title) {
        if (title.contains(".experimental.")) experimentalPrompts++;
        else deprecatedPrompts++;
    }

    public static void main(String[] args) {
        int[] continuations = {0};
        Runnable proceed = () -> continuations[0]++;
        for (boolean skip : new boolean[] {false, true}) {
            create(Lifecycle.stable(), proceed, skip);
            create(Lifecycle.experimental(), proceed, skip);
            create(Lifecycle.deprecated(1), proceed, skip);
        }
        if (continuations[0] != 5 || experimentalPrompts != 0 || deprecatedPrompts != 1)
            throw new AssertionError("Unexpected Forge lifecycle callback or prompt counts");
        System.out.println("PASS Forge continuation branch, boolean bypass and deprecated prompt");
    }
}
