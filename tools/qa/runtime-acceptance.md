# Pack-owned launcher runtime QA

This repository owns the identity, fixture defaults and historical content scenarios in `launcher-runtime-contract.json`. They are QA inputs only: no game options, distributed JVM settings, JARs or locks are changed. The declared loader must match `portable-pack.json`; changing the pack platform requires reviewing this QA declaration too.

The three retired helper prefixes were moved from the shared launcher's historical probe. Their authoritative exact paths, sizes and SHA256 already live in [../legacy-files.json](../legacy-files.json). The shared migration runner still verifies the actual archived manifest and bytes; this contract does not replace those pins.

Only this pack's existing source checks and publication workflow apply to a pack-only release. Do not run LANMinecraft's full/core Release gate again merely to publish these QA files. If launcher runtime/adapter code changes, its own universal launcher gate remains necessary. Existing [../../.github/workflows/publish.yml](../../.github/workflows/publish.yml) continues to validate actual content before publication.

Use the shared `tools/ForgeRuntimeQa` and `tools/PackMigrationQa` from a reviewed launcher source checkout. Build that specific tool once; this thin entrypoint does not build or execute anything automatically:

```powershell
tools/qa/launcher-runtime.ps1 -LauncherRoot <launcher-checkout> -RunnerArguments @('prepare', '<new-artifacts/agent-runs/task/fixture>', '--source', '<independent-pack-source>', '--mode', 'baseline')
tools/qa/launcher-runtime.ps1 -LauncherRoot <launcher-checkout> -RunnerArguments @('launch', '<fixture>', '--label', 'baseline-01', '--agent', '<forge-runtime-qa-agent.jar>', '--fresh-world', 'ATM9ModQaFresh')
tools/qa/launcher-runtime.ps1 -LauncherRoot <launcher-checkout> -Tool Migration -RunnerArguments @('<old-installation>', '<staged-distribution>')
```

The native runner is scoped to Minecraft 1.20.1 Forge, shared by all packs on that platform. It waits for explicit `ready` after the actual menu; `audit` and `stop` retain the same native lifecycle guards. Runtime acceptance and comparison follow the latest agreed scope for this pack, including actual client/world readiness and explicit analysis of changed ERROR signatures. A prepared manifest or server Done is not a full-client result. Unperformed visual, two-account, Steam or model-conversation checks remain unverified.

Existing evidence under `tools/qa` keeps its original revision and limitations. This QA-only separation does not claim a fresh gameplay PASS or rerun a test. Use one current independent stand, retain its `RUN.json`, check the shared 50 GiB budget, and remove owned bulky runtime copies after producing the compact reports. Personal worlds and active installations are never the stand.

## The Illusioner encounter

`IllusionerQaAgent.java` wraps the common lifecycle agent. Compile both sources
with `javac --release 17` and package their class files in one QA JAR with
`Premain-Class: IllusionerQaAgent`. Use that JAR for `launch --agent` in the
marked fixture. It activates only after the ordinary `ready` signal and
accepts inputs only in a reserved `ATM9ModQa...` world with one player.
For a new `--fresh-world ATM9ModQaFresh...` run, the agent disables the
client's pause-on-focus-loss option in memory so the encounter can tick
without foreground interaction. QuickPlay and migrated-world runs keep the
existing option value.

Write a single command to a new UTF-8 file beside `observations.jsonl`, then
rename it to `illusioner.request`. Pending requests wait for the client
loading overlay to clear. Wait for the corresponding result in
`illusioner-evidence.log` before sending another command. Use a fresh
evidence label for every client process.

- `mark` places three amethyst shards in the old technical player's inventory.
  `marker` reads the amethyst shard count in the current player's server inventory.
- `weakness` reads the current player's native Weakness effect, including its
  remaining ticks and zero-based amplifier when present.
- `anime-replacement` checks that Awakened Gas, Granolah and Black Frieza
  are absent from the Forge entity registry. It then gives one Medusa and
  verifies the server inventory received it. Use it in the migrated technical
  world.
- `keybindings` records the live Options key mappings after startup at the
  title screen or in the world. Compare the IDs and defaults in
  `keybindings.tsv` with the reviewed controls preset. This capture does not
  apply the preset or verify physical key presses.
- `smoke` samples the current 18 new anime mods and Whispering Spirits in
  an `ATM9ModQaFresh...` world with at least 16 free inventory slots and
  no active Illusioner encounter. It compares native item grant results with
  server inventory counts, checks the Goku and Fern painting registrations,
  and summons the authored Whispering Spirit with AI disabled. It leaves
  the samples in the disposable world. Review all
  19 individual results in `illusioner-evidence.log`.
- `locate` runs the native mansion locator and records its X/Z from the
  resulting chat component. Use these coordinates to
  reach the mansion with `tp x y z`, then run `find-note` near the main floor.
  That reads the loaded chunks and reports a sculk-topped note block inside
  the actual generated basement. Move within reach and run `strike x y z`
  to exercise the vanilla note-block attack and the authored encounter trigger.
- Wait for `FIGHT_ACTIVE` and note the unawakened boss. Play normally or use
  `survival`, `protect` and individual `damage` requests to progress through
  the health bars on the native damage path. For a code-only fight, arm
  `platform` before the first damage. The agent uses native teleport to move
  to the authored real disks in the mandatory Shattered Path attack, up to
  12 times. The attack itself checks landing and controls progression. Wait for the awakened state
  before progressing to the finisher. A kneeling boss must become finishable
  before accepting the final hit. Allow the native victory and return sequence
  to end, reopen the world, and query `defeated x y z` at the same basement.
- `shader-off` and `shader-on` select the default pipeline and the shipped
  MakeUp-UltraFast-9.3e shader via the native client API. `shot-default` and
  `shot-shader` record the current framebuffer under the evidence directory.
  Review both images and exercise the encounter before recording visual PASS.
- `publish` presses the actual Open to LAN button in an integrated fixture.
  `publish-api` calls the launcher's production auto-publish hook without
  opening the screen, using the same integrated-server bind and discovery path.
  After the Steam session is ready, `steam` checks the native chat and matches
  the current world and player against the live lobby's discovery fields. It
  records no address or account ID and does not establish guest connectivity.

The QA JAR stays outside the shipped mods and does not transform any classes.

## Preservation during author updates

These four files are repository-owned QA and publication controls, separate from the author's game payload:

- `tools/qa/launcher-runtime-contract.json`
- `tools/qa/launcher-runtime.ps1`
- `tools/qa/runtime-acceptance.md`
- `.github/workflows/publish.yml`

The current author importer and restore tools operate on named, pinned game files rather than replacing the repository. The publication generator clears only its staged output assets; it excludes `tools` from the distributed payload. These routes leave the four source-owned files intact. Owner snapshots made by `update_atm9_quests.py` include all `tools/**`, so `source_controls` and the snapshot validation/rehearsal route also preserve and check the three QA files. The workflow is outside that snapshot; the current import, restore and publication routes do not modify or remove it.

A manual integration that replaces the whole checkout with a fresh author tree must explicitly carry forward all four files from the reviewed repository before publication. Keep them as source-owned controls, never as author game content. Review the QA contract, fixture defaults and expected JAR/helper scenarios against the new Minecraft version, loader/platform and actual content; update expectations only after that review. Preserved QA files and historical evidence do not establish a new gameplay PASS for the updated author release.
