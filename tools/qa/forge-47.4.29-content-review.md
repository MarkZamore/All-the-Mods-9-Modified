# Forge 47.4.29 and content review

The previous public delivery was `a73c77cea71802fa1faef20421b79eea074e758e`.
Its exact manifest was used to install the technical baseline with Minecraft
1.20.1, Forge 47.4.0 and Java 17. A new technical world saved three amethyst
shards in the player inventory and the client exited normally. The first
production sync to the candidate verified 1,918 delivered files and retained
837 world and player files byte for byte.

- Core Lib 1.1.18 failed before mod initialization with Forge 47.4.29.
  Removing The Illusioner reproduced the same failure. Forge changed the
  `ObjectHolderRegistry.applyObjectHolders` argument from `Predicate` to
  `ResourceLocation`. The exact official Core Lib 1.1.26b handles both
  signatures. All nine installed dependents accept that release. The former
  `ModelGenerator.ElementBuilder.shape(FFIFFF)` is used by Connected Glass's
  developer data generator. In-game models use the packaged resources.
  [Official Core Lib file](https://www.curseforge.com/minecraft/mc-mods/supermartijn642s-core-lib/files/9689182)
- The Illusioner and Shooting Star 1.5.1 both redirect the same screen call
  at priority 1000. Forge stops loading when Shooting Star misses its
  required redirect. Without The Illusioner, the next client startup cleared
  that injection. The pack-owned compatibility agent combines the two
  screen decisions while retaining both author JARs. Its Java 17 probe
  verifies the exact Forge callsite and all four combinations of the two
  active flags. See [the compatibility source and build](../illusioner-compat/README.md).
- A shader reload in the reopened mansion world crashed when a Burnt block
  tick called Lucent's lighting hook on the integrated server thread while
  Embeddium was replacing its chunk render manager. The Forge client
  compatibility agent gates Lucent's hook to the logical client. The pinned
  Forge packet handlers apply replicated block updates on the client thread
  through the same hook. The Java 17 probe covers successful and failed
  writes on both sides. Lucent, Embeddium and the author's Mekalus JARs are
  delivered as their reviewed originals.
- My Hero Craft was replaced with My Crafting Academia after bytecode review.
  My Hero Craft altered Minecraft's movement validation for all players.
  The subsequent native player save exposed a separate issue in My Crafting
  Academia. Its first-login roll selected Sugar Rush. The server then renewed
  Weakness I for 20 ticks on each player tick while the ability was inactive.
  The final roster uses SootSpriteCraft, which has no player-login or HUD hooks.
  [Official SootSpriteCraft file](https://www.curseforge.com/minecraft/mc-mods/sootspritecraft/files/7413740)
- CHAINSAW-CRAFT registers the `[Main]` and `Cost-` overlay without requiring
  an interaction. The owner requested its replacement. Weapons of the Multiverse
  has no client HUD event or player-login hook in the exact approved JAR.
  [Official Weapons of the Multiverse file](https://www.curseforge.com/minecraft/mc-mods/weapons-of-the-multiverse/files/6058245)
- Frieren Craft Neo initializes mana at login and Noon’s Anime initializes a
  chakra HUD at login. Cobblemon’s first-login starter prompt has no timer and
  stays until interaction. The owner requested replacements for passive HUDs.
  Sousou no Frieren Paintings uses the dedicated painting atlas. Emoflix’s Swords
  and Scuffamon add items and entities without corresponding default HUD hooks.
  [Frieren Paintings](https://www.curseforge.com/minecraft/mc-mods/sousou-no-frieren-paintings/files/7432250) · [Emoflix’s Swords](https://www.curseforge.com/minecraft/mc-mods/emoflixs-swords/files/6693914) · [Scuffamon](https://www.curseforge.com/minecraft/mc-mods/scuffamon/files/5166769)
- Block Clover's 2048×2048 Demon Slasher texture prevented the existing
  16,384-pixel Minecraft block atlas from stitching during the comparison
  run. The replacement Craft Clover by Pandaviews uses nine item and block
  textures with dimensions up to 64 pixels and the existing Curios and
  Pehkui libraries. [Official Craft Clover file](https://www.curseforge.com/minecraft/mc-mods/craft-clover/files/5876750)

The 40 horror and 20 anime entries count active content IDs in the pack's
published groups. RunicLib is the dependency of Dungeon's Delight and is
excluded from the content count. The pack owner confirmed permission to
publish all selected files and bundled resources.

## Runtime and release candidate

- The launcher opened the migrated technical world with its three amethyst
  shards and saved it normally. Java 17 loaded the final roster on Forge
  47.4.29 in a newly generated world. The native ModList matches 558 pack mod
  IDs from 533 pinned top-level JARs, plus Minecraft, Forge and the Steam
  infrastructure mod. The fresh player's Weakness effect was absent. The
  final-roster smoke check passed 19 registry and gameplay operations,
  including the Whispering Spirit spawn.
- The package was checked for always-on HUD hooks in the 18 newly added anime
  JARs. Dungeon's Delight has effect-dependent overlays that stay transparent
  when the respective effects are inactive. The other 17 JARs in that set
  have no Forge HUD registrations.
- The world-generation locator returned a mansion in new terrain. Striking
  its basement note block started the encounter. Native server commands
  exercised damage and movement to the authored platforms while the boss
  ran its normal logic. The log recorded both phases, the finishing window,
  victory, and transfer back to the Overworld. The game saved the mansion's
  defeat and exited with code 0. After a full client restart, the same
  mansion remained defeated at the saved note block position.
- After applying the Lucent fix, the same saved world opened with the
  victory flag intact. Two reloads from stock rendering to MakeUp-UltraFast
  9.3e and back completed. Native captures in the two modes decoded as
  distinct 960×540 frames. The final roster stitched the block and painting
  atlases. Steam created a real lobby after the shader changes. Discovery
  matched the world title and host's nickname, and the chat message appeared
  in the local and Steam paths. The fixture had one Steam account, so a
  guest join and rejoin were not exercised.
- The same two Slice & Dice conversion warnings for Delightful's matcha
  latte and ender nectar recipes were present on the previous public pack.
- Restoration, 268 pack tests, four targeted keybind tests, the preset
  verifier, 231 staged payload assets, the library archive and the Steam-only
  server policy all passed. The staged manifest lists 1,919 files.
