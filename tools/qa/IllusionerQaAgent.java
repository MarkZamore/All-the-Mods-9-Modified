import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Test-only observer and bounded native inputs for disposable Forge QA worlds. */
public final class IllusionerQaAgent {
    private static final String MC = "net.minecraft.";
    private static final String MOD = "com.fqkeh.illusioner.";
    private static final List<SmokeSample> SMOKE_SAMPLES = List.of(
        new SmokeSample(Registry.ITEMS, "anime_inventory_pets:pikachu_pet"),
        new SmokeSample(Registry.ITEMS, "azumangacraft:chiyo_chichi_plush"),
        new SmokeSample(Registry.ITEMS, "block_clover:flame_grimoire"),
        new SmokeSample(Registry.PAINTING_VARIANTS, "brizzles_anime_paintings:goku"),
        new SmokeSample(Registry.ITEMS, "weaponsofthemultiverse:elucidator_sword"),
        new SmokeSample(Registry.ITEMS, "scuffamon:magikarp_spawn_egg"),
        new SmokeSample(Registry.ITEMS, "demonslayerswords:rengokusword"),
        new SmokeSample(Registry.ITEMS, "dragonbcraft:goku_spawn_egg"),
        new SmokeSample(Registry.ITEMS, "dungeonsdelight:monster_pot"),
        new SmokeSample(Registry.PAINTING_VARIANTS, "sousou_no_frieren_paintings:fern"),
        new SmokeSample(Registry.ITEMS, "sootspritecraft:star_candy"),
        new SmokeSample(Registry.ITEMS, "narutos_hitaiate:hitaiate_armor_helmet"),
        new SmokeSample(Registry.ITEMS, "nenus_pop_plushies:totoro_plush"),
        new SmokeSample(Registry.ITEMS, "emoflix:zanpakuto"),
        new SmokeSample(Registry.ITEMS, "pkgbadges:trainer_backpack"),
        new SmokeSample(Registry.ITEMS, "sailor_moon:space_sword"),
        new SmokeSample(Registry.ITEMS, "hutheeraofpirates:shusui"),
        new SmokeSample(Registry.ITEMS, "zoni_mod:excalibur"),
        new SmokeSample(Registry.ENTITY_TYPES, "whispering_spirits:whispering_spirit"));
    private final Path root, game, evidence;
    private final String worldPrefix;
    private final boolean backgroundFreshWorld;
    private ClassLoader loader;
    private Object client;
    private Object lastMansion;
    private Object platformEncounter;
    private int lastAssistedDisk;
    private int platformMoves;
    private boolean backgroundConfigured;
    private String previousState = "";

    private IllusionerQaAgent() throws Exception {
        root = propertyPath("root");
        game = propertyPath("game");
        evidence = propertyPath("evidence");
        worldPrefix = System.getProperty("lanminecraft.qa.worldPrefix", "");
        String freshWorld = System.getProperty("lanminecraft.qa.freshWorld", "");
        backgroundFreshWorld = freshWorld.matches(worldPrefix + "Fresh[A-Za-z0-9_-]{0,43}");
        if (!root.toString().replace('\\', '/').contains("/artifacts/agent-runs/")
            || !Files.isRegularFile(root.resolve(".forge-runtime-qa.json"), LinkOption.NOFOLLOW_LINKS)
            || !game.startsWith(root.resolve("Minecraft/Personal/Instances"))
            || !evidence.getParent().equals(root.resolve("evidence"))
            || !worldPrefix.equals("ATM9ModQa")) {
            throw new IllegalArgumentException("Expected an isolated ATM9 Modified runtime QA fixture");
        }
    }

    public static void premain(String ignored, Instrumentation instrumentation) {
        QaLifecycleAgent.premain(ignored, instrumentation);
        try {
            var agent = new IllusionerQaAgent();
            Thread observer = new Thread(() -> agent.run(instrumentation), "Illusioner-QA");
            observer.setDaemon(true);
            observer.start();
        } catch (Exception failure) {
            System.err.println("[Illusioner QA] " + failure);
        }
    }

    private void run(Instrumentation instrumentation) {
        long deadline = System.nanoTime() + TimeUnit.HOURS.toNanos(1);
        Path request = evidence.resolve("illusioner.request");
        try {
            while (System.nanoTime() < deadline && !Files.exists(evidence.resolve("stop.flag"))) {
                Thread.sleep(1200);
                if (!Files.isRegularFile(evidence.resolve("ready.flag"), LinkOption.NOFOLLOW_LINKS)) continue;
                if (client == null) for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                    if (!type.getName().equals(MC + "client.Minecraft")) continue;
                    loader = type.getClassLoader();
                    Thread.currentThread().setContextClassLoader(loader);
                    client = method(type, "m_91087_").invoke(null);
                    break;
                }
                if (client == null) continue;
                ClientState clientState;
                try {
                    clientState = on(client, () -> {
                        if (!((File) field(client.getClass(), "f_91069_").get(client)).toPath().toRealPath().equals(game))
                            throw new SecurityException("Wrong game directory");
                        boolean loading = call(client, "m_91265_") != null;
                        Object server = call(client, "m_91092_");
                        if (backgroundFreshWorld && !backgroundConfigured && !loading) {
                            configureBackground(server);
                        }
                        return new ClientState(server, loading);
                    });
                } catch (TimeoutException busy) {
                    // Resource reload and initial world loading can stall client-thread polling.
                    continue;
                }
                if (clientState.loading()) continue;
                Object server = clientState.server();
                if (Files.isRegularFile(request, LinkOption.NOFOLLOW_LINKS) && Files.size(request) <= 128
                    && Files.readString(request).trim().equals("keybindings")) {
                    Files.delete(request);
                    try { log("request keybindings => " + on(client, this::keybindings)); }
                    catch (Exception failure) { log("request_failed keybindings => " + detail(failure)); }
                }
                if (server == null) continue;
                try {
                    String state = on(server, () -> inspect(server, null));
                    // An integrated server pauses at menus. A timeout allows the client to keep rendering.
                    if (state == null) continue;
                    if (!state.equals(previousState)) { log("state " + state); previousState = state; }
                    if (Files.isRegularFile(request, LinkOption.NOFOLLOW_LINKS)) {
                        if (Files.size(request) > 128) throw new IllegalArgumentException("Oversized QA request");
                        String command = Files.readString(request).trim();
                        Files.delete(request);
                        try {
                            String result;
                            if (command.equals("publish")) result = on(client, this::publish);
                            else if (command.equals("publish-api")) result = on(client, () -> publishApi(state));
                            else if (command.equals("steam")) result = steam(state);
                            else if (command.equals("shader-on") || command.equals("shader-off"))
                                result = on(client, () -> shader(command.equals("shader-on")));
                            else if (command.equals("shot-default") || command.equals("shot-shader"))
                                result = on(client, () -> screenshot(command));
                            else if (command.equals("locate") || command.equals("smoke"))
                                result = on(server, () -> inspect(server, command), 120);
                            else result = on(server, () -> inspect(server, command));
                            log("request " + command + " => " + result);
                        } catch (Exception failure) { log("request_failed " + command + " => " + detail(failure)); }
                    }
                } catch (TimeoutException ignored) { }
            }
        } catch (Exception failure) {
            try { log("observer_failed " + detail(failure)); } catch (Exception ignored) { }
        }
    }

    private record ClientState(Object server, boolean loading) { }
    private enum Registry { ITEMS, ENTITY_TYPES, PAINTING_VARIANTS }
    private record SmokeSample(Registry registry, String id) { }

    private void configureBackground(Object server) throws Exception {
        Object options = field(client.getClass(), "f_91066_").get(client);
        Field pause = field(options.getClass(), "f_92126_");
        boolean previous = pause.getBoolean(options);
        pause.setBoolean(options, false);
        Object screen = field(client.getClass(), "f_91080_").get(client);
        if (server != null && type(MC + "client.gui.screens.PauseScreen").isInstance(screen))
            method(client.getClass(), "m_91152_", type(MC + "client.gui.screens.Screen")).invoke(client, (Object) null);
        backgroundConfigured = true;
        log("backgroundFreshWorld pauseOnLostFocus=false original=" + previous);
    }

    private String inspect(Object server, String command) throws Exception {
        String world = (String) call(call(server, "m_129910_"), "m_5462_");
        if (!world.matches(worldPrefix + "[A-Za-z0-9_-]{1,48}")) return null;
        List<?> players = (List<?>) call(call(server, "m_6846_"), "m_11314_");
        if (players.size() != 1) return null;
        Object player = players.get(0);
        Object level = call(player, "m_9236_");
        Object position = call(player, "m_20183_");
        Object encounter = null;
        boolean illusionerPresent = true;
        try {
            Object manager = method(type(MOD + "encounter.EncounterManager"), "get").invoke(null);
            Collection<?> encounters = (Collection<?>) call(manager, "all");
            encounter = encounters.stream().findFirst().orElse(null);
        } catch (ClassNotFoundException absent) { illusionerPresent = false; }
        Object boss = null;
        if (encounter != null) {
            var id = (UUID) call(field(encounter.getClass(), "boss").get(encounter), "bossId");
            if (id != null) boss = method(level.getClass(), "m_8791_", UUID.class).invoke(level, id);
            var mansion = (Optional<?>) call(encounter, "mansion");
            if (mansion.isPresent()) lastMansion = mansion.get();
        }
        String dimension = String.valueOf(call(call(level, "m_46472_"), "m_135782_"));
        if (command == null) {
            if (platformEncounter != null && platformEncounter != encounter) platformEncounter = null;
            if (boss != null && platformEncounter == encounter && platformEncounter != null)
                assistPlatform(server, player, boss);
            String state = "world=" + world + " dimension=" + dimension + " illusioner=" + illusionerPresent + " encounter="
                + (encounter == null ? "absent" : call(encounter, "phase"))
                + (boss == null ? "" : " awakened=" + call(boss, "isAwakened") + " kneeling="
                    + call(boss, "isKneeling") + " finishable=" + call(boss, "isFinishable") + " health=" + call(boss, "m_21223_"));
            return lastMansion == null ? state : state + " mansionDefeated=" + defeated(server, lastMansion);
        }
        String[] words = command.split(" ");
        if (words.length == 4 && words[0].equals("tp")) {
            int[] p = coordinates(words);
            if (encounter != null) throw new IllegalStateException("Teleport disabled during encounters");
            return "nativeResult=" + perform(server, player, "tp @s " + p[0] + " " + p[1] + " " + p[2]);
        }
        if (words.length == 4 && (words[0].equals("strike") || words[0].equals("defeated"))) {
            int[] p = coordinates(words);
            Object pos = type(MC + "core.BlockPos").getConstructor(int.class, int.class, int.class).newInstance(p[0], p[1], p[2]);
            Object site = basement(level, pos).orElseThrow(() -> new IllegalStateException("Not an authored basement"));
            lastMansion = call(site, "mansion");
            if (words[0].equals("defeated")) return "mansionDefeated=" + defeated(server, lastMansion);
            Object blockState = method(level.getClass(), "m_8055_", pos.getClass()).invoke(level, pos);
            Object block = call(blockState, "m_60734_");
            if (!note(level, pos, block)) throw new IllegalStateException("Expected sculk-topped note block");
            double dx = (double) call(player, "m_20185_") - p[0] - 0.5;
            double dy = (double) call(player, "m_20186_") - p[1] - 0.5;
            double dz = (double) call(player, "m_20189_") - p[2] - 0.5;
            if (dx * dx + dy * dy + dz * dz > 36) throw new IllegalStateException("Get within native reach of the note block");
            method(block.getClass(), "m_6256_", blockState.getClass(), type(MC + "world.level.Level"),
                pos.getClass(), type(MC + "world.entity.player.Player")).invoke(block, blockState, level, pos, player);
            return "nativeNoteBlockAttack=true";
        }
        return switch (command) {
            case "locate" -> locate(server, player);
            case "find-note" -> findNote(level, position);
            case "survival" -> "nativeResult=" + perform(server, player, "gamemode survival @s");
            case "protect" -> "nativeResult=" + perform(server, player, "effect give @s minecraft:resistance 1200 4 true");
            case "mark" -> "nativeResult=" + perform(server, player, "give @s minecraft:amethyst_shard 3");
            case "marker" -> "amethystShards=" + inventoryCount(player,
                field(type(MC + "world.item.Items"), "f_151049_").get(null));
            case "weakness" -> weakness(player);
            case "smoke" -> smoke(server, player, world, encounter != null);
            case "platform" -> {
                if (!world.startsWith(worldPrefix + "Fresh") || !dimension.equals("illusioner:corrupted_void") ||
                    boss == null || !String.valueOf(call(encounter, "phase")).equals("FIGHT_ACTIVE") ||
                    ((Optional<?>) call(encounter, "mansion")).isEmpty() || platformEncounter == encounter ||
                    (boolean) call(boss, "isAwakened"))
                    throw new IllegalStateException("Platform assistance requires a fresh mansion fight before ascension");
                platformEncounter = encounter;
                lastAssistedDisk = -1;
                platformMoves = 0;
                yield "platformAssist=armed";
            }
            case "damage" -> {
                if (boss == null || !Set.of("FIGHT_ACTIVE", "FINISHER").contains(String.valueOf(call(encounter, "phase"))))
                    throw new IllegalStateException("The authored boss is not in a damageable phase");
                if ((boolean) call(boss, "isKneeling") && !(boolean) call(boss, "isFinishable"))
                    throw new IllegalStateException("Wait for the native finisher window");
                yield "nativeResult=" + perform(server, player, "damage " + call(boss, "m_20148_")
                    + " 200 minecraft:player_attack by " + call(player, "m_20148_"));
            }
            default -> throw new IllegalArgumentException("Unknown QA request");
        };
    }

    private String findNote(Object level, Object position) throws Exception {
        int centerX = (int) call(position, "m_123341_");
        int centerY = (int) call(position, "m_123342_");
        int centerZ = (int) call(position, "m_123343_");
        var constructor = type(MC + "core.BlockPos").getConstructor(int.class, int.class, int.class);
        var state = method(level.getClass(), "m_8055_", constructor.getDeclaringClass());
        var loaded = method(level.getClass(), "m_7232_", int.class, int.class);
        Class<?> noteBlock = type(MC + "world.level.block.NoteBlock");
        Method getBlock = method(type(MC + "world.level.block.state.BlockState"), "m_60734_");
        for (int x = centerX - 48; x <= centerX + 48; ++x) for (int z = centerZ - 48; z <= centerZ + 48; ++z) {
            if (!(boolean) loaded.invoke(level, x >> 4, z >> 4)) continue;
            for (int y = centerY - 40; y <= centerY + 8; ++y) {
                Object pos = constructor.newInstance(x, y, z);
                Object block = getBlock.invoke(state.invoke(level, pos));
                if (!noteBlock.isInstance(block) || !note(level, pos, block)) continue;
                var site = basement(level, pos);
                if (site.isPresent()) return "authoredNote=" + x + "," + y + "," + z;
            }
        }
        return "authoredNote=not_found_in_loaded_area";
    }

    private Optional<?> basement(Object level, Object pos) throws Exception {
        return (Optional<?>) method(type(MOD + "progression.MansionBasement"), "at",
            type(MC + "server.level.ServerLevel"), type(MC + "core.BlockPos")).invoke(null, level, pos);
    }

    private boolean note(Object level, Object pos, Object block) throws Exception {
        return type(MC + "world.level.block.NoteBlock").isInstance(block) &&
            (boolean) method(type(MOD + "progression.SculkNoteBlockTrigger"), "topped",
                type(MC + "server.level.ServerLevel"), type(MC + "core.BlockPos")).invoke(null, level, pos);
    }

    private boolean defeated(Object server, Object mansion) throws Exception {
        Object data = method(type(MOD + "progression.DefeatedMansions"), "get",
            type(MC + "server.MinecraftServer")).invoke(null, server);
        return (boolean) method(data.getClass(), "isDefeated", mansion.getClass()).invoke(data, mansion);
    }

    private int perform(Object server, Object player, String command) throws Exception {
        return performAs(server, call(player, "m_20203_"), command);
    }

    private int performAs(Object server, Object source, String command) throws Exception {
        Object commands = call(server, "m_129892_");
        return (int) method(commands.getClass(), "m_230957_", type(MC + "commands.CommandSourceStack"),
            String.class).invoke(commands, source, command);
    }

    private int inventoryCount(Object player, Object item) throws Exception {
        Object inventory = call(player, "m_150109_");
        return (int) method(inventory.getClass(), "m_18947_", type(MC + "world.item.Item"))
            .invoke(inventory, item);
    }

    private String weakness(Object player) throws Exception {
        Object weakness = field(type(MC + "world.effect.MobEffects"), "f_19613_").get(null);
        Object effect = method(player.getClass(), "m_21124_", type(MC + "world.effect.MobEffect"))
            .invoke(player, weakness);
        return effect == null ? "weakness=absent" : "weakness=present durationTicks=" +
            call(effect, "m_19557_") + " amplifier=" + call(effect, "m_19564_");
    }

    private void assistPlatform(Object server, Object player, Object boss) throws Exception {
        if ((boolean) call(boss, "isAwakened") || platformMoves >= 12) {
            platformEncounter = null;
            log("platformAssist=disarmed moves=" + platformMoves);
            return;
        }
        Object brainSlot = field(boss.getClass(), "ai").get(boss);
        Object brain = field(brainSlot.getClass(), "brain").get(brainSlot);
        if (brain == null) return;
        Object ai = field(brain.getClass(), "bossAi").get(brain);
        Object foreground = field(ai.getClass(), "foreground").get(ai);
        Object attack = field(foreground.getClass(), "attack").get(foreground);
        if (attack == null ||
            !attack.getClass().getName().equals(MOD + "boss.illusioner.illusion.platform.PlatformRunAttack"))
            return;
        if (field(attack.getClass(), "failed").getBoolean(attack)) {
            platformEncounter = null;
            log("platformAssist=expired before native platform claim");
            return;
        }
        var platforms = (List<?>) field(attack.getClass(), "next").get(attack);
        if (platforms.isEmpty()) return;
        Object next = call(attack, "realNext");
        int id = (int) call(next, "id");
        if (id == lastAssistedDisk) return;
        Object platformWorld = field(attack.getClass(), "world").get(attack);
        if (!((List<?>) field(platformWorld.getClass(), "physical").get(platformWorld)).contains(next)) return;
        Object origin = call(next, "origin");
        int x = (int) call(origin, "m_123341_");
        int y = (int) call(origin, "m_123342_");
        int z = (int) call(origin, "m_123343_");
        int result = perform(server, player, "tp @s " + (x + 0.5) + " " + (y + 0.0625) + " " + (z + 0.5));
        ++platformMoves;
        if (result == 1) lastAssistedDisk = id;
        log("platformAssist disk=" + id + " x=" + x + " y=" + y + " z=" + z +
            " nativeTeleport=" + result);
    }

    private String smoke(Object server, Object player, String world, boolean activeEncounter) throws Exception {
        if (!world.startsWith(worldPrefix + "Fresh") || activeEncounter)
            throw new IllegalStateException("Smoke requires a fresh technical world outside an Illusioner encounter");
        Class<?> registries = type("net.minecraftforge.registries.ForgeRegistries");
        Class<?> registryType = type("net.minecraftforge.registries.IForgeRegistry");
        Class<?> resourceType = type(MC + "resources.ResourceLocation");
        Method contains = method(registryType, "containsKey", resourceType);
        Method get = method(registryType, "getValue", resourceType);
        var resource = resourceType.getConstructor(String.class);
        int passed = 0;
        for (SmokeSample sample : SMOKE_SAMPLES) {
            boolean success = false;
            String result;
            try {
                Object registry = field(registries, sample.registry().name()).get(null);
                Object id = resource.newInstance(sample.id());
                if (!(boolean) contains.invoke(registry, id)) {
                    result = "registry=missing";
                } else {
                    switch (sample.registry()) {
                        case ITEMS -> {
                            Object item = get.invoke(registry, id);
                            int before = inventoryCount(player, item);
                            int nativeResult = perform(server, player, "give @s " + sample.id() + " 1");
                            int added = inventoryCount(player, item) - before;
                            success = nativeResult == 1 && added == 1;
                            result = "nativeGive=" + nativeResult + " itemsAdded=" + added;
                        }
                        case ENTITY_TYPES -> {
                            int nativeResult = perform(server, player, "summon " + sample.id() + " ~2 ~ ~ {NoAI:1b}");
                            success = nativeResult == 1;
                            result = "nativeSummon=" + nativeResult;
                        }
                        case PAINTING_VARIANTS -> {
                            success = true;
                            result = "registry=present";
                        }
                        default -> throw new IllegalStateException("Unknown smoke registry");
                    }
                }
            } catch (Exception failure) {
                result = "error=" + detail(failure);
            }
            if (success) ++passed;
            log("smoke " + sample.id() + " " + (success ? "PASS " : "FAIL ") + result);
        }
        return "passed=" + passed + " expected=" + SMOKE_SAMPLES.size();
    }

    private String locate(Object server, Object player) throws Exception {
        Class<?> commandSource = type(MC + "commands.CommandSource");
        var feedback = new ArrayList<Object>();
        Object listener = Proxy.newProxyInstance(loader, new Class<?>[] {commandSource}, (proxy, callback, args) -> {
            if (callback.getName().equals("m_213846_")) feedback.add(args[0]);
            return callback.invoke(player, args);
        });
        Object original = call(player, "m_20203_");
        Object source = method(original.getClass(), "m_165484_", commandSource).invoke(original, listener);
        int result = performAs(server, source, "locate structure minecraft:mansion");
        for (Object message : feedback) {
            if (!translationKey(message).equals("commands.locate.structure.success")) continue;
            Object[] details = translationArguments(message);
            if (details.length != 3 || !translationKey(details[1]).equals("chat.square_brackets"))
                throw new IllegalStateException("Unexpected native mansion locate feedback");
            Object[] brackets = translationArguments(details[1]);
            if (brackets.length != 1 || !translationKey(brackets[0]).equals("chat.coordinates"))
                throw new IllegalStateException("Unexpected native mansion coordinate component");
            Object[] coordinates = translationArguments(brackets[0]);
            if (coordinates.length != 3 || !(coordinates[0] instanceof Integer x) || !(coordinates[2] instanceof Integer z))
                throw new IllegalStateException("Native mansion coordinates are not integers");
            return "nativeResult=" + result + " mansionXZ=" + x + "," + z;
        }
        return "nativeResult=" + result + " mansionXZ=unavailable";
    }

    private String publish() throws Exception {
        Class<?> screen = type(MC + "client.gui.screens.Screen");
        Object pause = type(MC + "client.gui.screens.PauseScreen").getConstructor(boolean.class).newInstance(true);
        method(client.getClass(), "m_91152_", screen).invoke(client, pause);
        Object button = null;
        for (Object child : (List<?>) call(pause, "m_6702_")) {
            if (!type(MC + "client.gui.components.AbstractButton").isInstance(child)) continue;
            if (!"menu.shareToLan".equals(translationKey(call(child, "m_6035_")))) continue;
            if (button != null) throw new IllegalStateException("Ambiguous Open to LAN action");
            button = child;
        }
        if (button == null || !(boolean) field(button.getClass(), "f_93623_").get(button))
            throw new IllegalStateException("Native Open to LAN action unavailable");
        call(button, "m_5691_");
        return "nativeOpenToLanButton=true";
    }

    private String publishApi(String state) throws Exception {
        if (!state.contains(" encounter=absent") ||
            !Boolean.getBoolean("minecraft.portable.identity.lanPublishEnabled") ||
            !Boolean.getBoolean("minecraft.portable.identity.steamDiscoveryEnabled"))
            throw new IllegalStateException("Steam publication requires the normal launcher hook and an idle technical world");
        Class<?> screen = type(MC + "client.gui.screens.Screen");
        Object nativeForm = type(MC + "client.gui.screens.ShareToLanScreen").getConstructor(screen)
            .newInstance((Object) null);
        Class<?> hook = Class.forName("minecraft.portable.identity.PortableLanAutoPublishHooks", false, null);
        if (!Boolean.TRUE.equals(method(hook, "autoPublish", Object.class).invoke(null, nativeForm)))
            throw new IllegalStateException("The launcher's native LAN publication hook rejected the request");
        return "nativePublicationHook=true";
    }

    private String steam(String state) throws Exception {
        var proof = on(client, () -> {
            Object gui = field(client.getClass(), "f_91065_").get(client);
            Object chat = field(gui.getClass(), "f_92988_").get(gui);
            Set<String> keys = new HashSet<>();
            for (Object entry : (List<?>) field(chat.getClass(), "f_93760_").get(chat))
                keys.add(translationKey(call(entry, "f_240363_")));
            Object player = field(client.getClass(), "f_91074_").get(client);
            return new String[] {String.valueOf(call(call(player, "m_36316_"), "getName")),
                String.valueOf(keys.contains("commands.publish.started")),
                String.valueOf(keys.contains("text.e4steam_minecraft.domainAssigned"))};
        });
        String world = state.split(" ", 2)[0].substring("world=".length());
        Class<?> type = type("link.e4steam.steam.SteamRuntime");
        Object runtime = method(type, "get").invoke(null);
        Callable<String> read = () -> {
            Object host = field(type, "hostRegistration").get(runtime);
            Object manager = field(type, "lobbyManager").get(runtime);
            if (host == null || manager == null) return "lobby=unavailable";
            long handle = field(manager.getClass(), "hostLobbyId").getLong(manager);
            if (handle == 0) return "lobby=unavailable";
            if (!Objects.equals(field(manager.getClass(), "hostLobbyOwner").get(manager), call(host, "owner")))
                return "lobby=unconfirmed_owner";
            Class<?> steamId = type("com.codedisaster.steamworks.SteamID");
            Object lobby = method(steamId, "createFromNativeHandle", long.class).invoke(null, handle);
            Object matchmaking = field(manager.getClass(), "matchmaking").get(manager);
            Method get = method(matchmaking.getClass(), "getLobbyData", steamId, String.class);
            return "lobby=true worldMatches=" + world.equals(get.invoke(matchmaking, lobby, "lanminecraft_world"))
                + " playerMatches=" + proof[0].equals(get.invoke(matchmaking, lobby, "lanminecraft_player"))
                + " display=" + "1".equals(get.invoke(matchmaking, lobby, "lanminecraft_display"));
        };
        var task = (CompletableFuture<?>) method(type, "submitSteamTask", Callable.class).invoke(runtime, read);
        return task.get(8, TimeUnit.SECONDS) + " localChat=" + proof[1] + " steamChat=" + proof[2];
    }

    private String shader(boolean enabled) throws Exception {
        Class<?> iris = type("net.irisshaders.iris.Iris");
        Object config = method(iris, "getIrisConfig").invoke(null);
        String shader = "MakeUp-UltraFast-9.3e.zip";
        if (enabled) {
            Path pack = game.resolve("shaderpacks").resolve(shader);
            if (!Files.isRegularFile(pack, LinkOption.NOFOLLOW_LINKS) || !pack.toRealPath().startsWith(game))
                throw new IllegalStateException("The shipped QA shader is unavailable");
            method(config.getClass(), "setShaderPackName", String.class).invoke(config, shader);
        }
        method(iris, "toggleShaders", client.getClass(), boolean.class).invoke(null, client, enabled);
        Class<?> api = type("net.irisshaders.iris.api.v0.IrisApi");
        Object instance = method(api, "getInstance").invoke(null);
        boolean active = (boolean) method(api, "isShaderPackInUse").invoke(instance);
        if (active != enabled) throw new IllegalStateException("Shader pipeline did not enter the requested state");
        return "shader=" + (active ? shader : "off");
    }

    private String screenshot(String name) throws Exception {
        Path screenshots = evidence.resolve("screenshots");
        if (Files.exists(screenshots) && !screenshots.toRealPath().getParent().equals(evidence))
            throw new SecurityException("Linked screenshot directory");
        Path output = screenshots.resolve(name + ".png");
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Screenshot already requested");
        Object frame = call(client, "m_91385_");
        Consumer<Object> feedback = message -> {
            try { log("screenshot " + name + " " + translationKey(message)); } catch (Exception ignored) { }
        };
        method(type(MC + "client.Screenshot"), "m_92295_", File.class, String.class,
            type("com.mojang.blaze3d.pipeline.RenderTarget"), Consumer.class)
            .invoke(null, evidence.toFile(), output.getFileName().toString(), frame, feedback);
        return "nativeScreenshotRequested=" + output.getFileName();
    }

    private String keybindings() throws Exception {
        if (call(client, "m_91265_") != null)
            throw new IllegalStateException("Wait for the completed title screen or world");
        Object options = field(client.getClass(), "f_91066_").get(client);
        Object[] mappings = (Object[]) field(options.getClass(), "f_92059_").get(options);
        var rows = new ArrayList<String>();
        var ids = new HashSet<String>();
        for (Object mapping : mappings) {
            String name = (String) call(mapping, "m_90860_");
            String category = (String) call(mapping, "m_90858_");
            Object context = call(mapping, "getKeyConflictContext");
            String contextName = context instanceof Enum<?> value ? value.name() : context.getClass().getName();
            String current = (String) call(mapping, "m_90865_");
            String defaultKey = (String) call(call(mapping, "m_90861_"), "m_84874_");
            String modifier = ((Enum<?>) call(mapping, "getDefaultKeyModifier")).name();
            if (!modifier.equals("NONE")) defaultKey += ":" + modifier;
            String row = String.join("\t", name, category, mapping.getClass().getName(), contextName, current, defaultKey);
            if (row.chars().filter(value -> value == '\t').count() != 5 || row.contains("\n") || row.contains("\r"))
                throw new IllegalStateException("Invalid native key mapping text");
            rows.add(row);
            ids.add(name);
        }
        Collections.sort(rows);
        rows.add(0, "id\tcategory\tmappingClass\tcontext\tnativeValue\tdefaultValue");
        Files.writeString(evidence.resolve("keybindings.tsv"), String.join("\n", rows) + "\n", StandardOpenOption.CREATE_NEW);
        return "rows=" + mappings.length + " uniqueNames=" + ids.size();
    }

    private String translationKey(Object message) throws Exception {
        Object contents = call(message, "m_214077_");
        return type(MC + "network.chat.contents.TranslatableContents").isInstance(contents)
            ? (String) call(contents, "m_237508_") : "";
    }

    private Object[] translationArguments(Object message) throws Exception {
        return (Object[]) call(call(message, "m_214077_"), "m_237523_");
    }

    private static int[] coordinates(String[] words) {
        int[] p = {Integer.parseInt(words[1]), Integer.parseInt(words[2]), Integer.parseInt(words[3])};
        if (Math.abs((long) p[0]) > 28000000 || p[1] < -64 || p[1] > 320 || Math.abs((long) p[2]) > 28000000)
            throw new IllegalArgumentException("Coordinates outside QA range");
        return p;
    }

    private static <T> T on(Object executor, Callable<T> action) throws Exception {
        return on(executor, action, 12);
    }

    private static <T> T on(Object executor, Callable<T> action, int timeoutSeconds) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        ((Executor) executor).execute(() -> {
            if (future.isCancelled()) return;
            try { future.complete(action.call()); }
            catch (Throwable e) { future.completeExceptionally(e); }
        });
        try { return future.get(timeoutSeconds, TimeUnit.SECONDS); }
        catch (TimeoutException timeout) { future.cancel(false); throw timeout; }
    }

    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    private static Object call(Object value, String name) throws Exception { return method(value.getClass(), name).invoke(value); }
    private static Method method(Class<?> type, String name, Class<?>... args) throws ReflectiveOperationException {
        try {
            Method result = type.getMethod(name, args);
            if (!Modifier.isPublic(result.getDeclaringClass().getModifiers())) result.setAccessible(true);
            return result;
        } catch (NoSuchMethodException ignored) { }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Method result = current.getDeclaredMethod(name, args); result.setAccessible(true); return result; }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field result = current.getDeclaredField(name); result.setAccessible(true); return result; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
    private static Path propertyPath(String name) throws Exception {
        return Path.of(System.getProperty("lanminecraft.qa." + name)).toRealPath();
    }
    private void log(String text) throws Exception {
        Files.writeString(evidence.resolve("illusioner-evidence.log"), Instant.now() + " " + text + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    private static Throwable detail(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
}
