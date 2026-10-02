# Endless verification

The release gate requires **Validate** and **Required live verification** to pass
on the same final commit. A build, a successful login, or a representation smoke
test alone does not certify real player interaction at large heights.

## Layers

| Layer | Coverage | Cost control |
| --- | --- | --- |
| Core | Shared/versioned JUnit storage, codec, config, logical/dense geometry and migration regressions; NeoForge-backed allocator test | `testAllVersions`; loader storage is not mocked |
| Harness | Scenario selection, marker failures, commit receipts, concurrent-run exclusion and failure evidence | Python standard library, no game boots |
| Extended gameplay | Commands, boundaries, blocks/fluids/block entities, POIs, lighting, scheduled mechanics, persistence reload, Waystones, navigation, real client place/break and acknowledged prediction | Shared small fixtures at both edges |
| Million gameplay | The same complete gameplay assertions in `[-1,048,576, 1,048,576)` | Reuses the extended scenario; bounded X/Z and vertical windows |
| Representation envelope | Sparse access, POIs and page packet/storage round trips near Â±8,000,000 | Lightweight smoke, no full-height scans |
| Cold restart | Blocks, fluids, block entities, redstone, light and POIs near Â±8,000,000 after a fresh server JVM | Two launches reuse only that scenario's saved world |
| Same-JVM rejoin | Automated save/close/reopen in one client JVM on both a migrated legacy Y=1,000,000 save and a fresh +/-8M world at Y=6,000,000; generic block-entity registration, completed render-section membership and visible ordinary solid meshes at distance 12; dense canary preservation | Shell/CI launches the self-driving graphical client; no manual navigation |
| Create compatibility | Pinned Forge 1.20.1 / Create 6.0.8 and NeoForge 1.21.1 / Create 6.0.11: rails, kinetic identity, independent fresh-JVM persistence, connected legacy migration, corrupt-storage refusal, and exact mounted-contraption disk/spawn serialization, client interaction, and machine lifecycle boundary regressions | Two existing scenarios per supported Create target; extra probes stay inside cold restart |
| Compatibility baselines | Vanilla-range Endless server and genuine vanilla server, including stale client range reset | No gameplay compatibility dependencies |

The live matrix contains **44 required cells**: 16 Fabric/Forge 1.20.1 cells, four Create compatibility/cold-restart cells, eleven native Create gameplay cells, two survival player-workflow cells, two train-workflow cells, three port-runtime cells for Fabric 1.21.1 / NeoForge 1.21.1 / NeoForge 26.1.2, and six same-JVM rejoin cells across all three modern targets. The rejoin pair per modern target covers both the migrated 254-section legacy dense layout at Y=1,000,000 and the exact fresh full-envelope regression at Y=6,000,000 with render distance 12. Each rejoin creates the world, installs multiple vanilla block-entity types, saves and shuts down the integrated server, waits for the exact server thread to release the world lock, reopens the same save in the same client JVM, and requires every block entity to appear in a completed render section without interaction. Block-entity evidence uses full XYZ coordinates rather than vanilla's packed `BlockPos.asLong()` representation. The dense canary must also survive and render again.

The harness launches graphical clients itself. Linux uses `xvfb-run` when `DISPLAY` is absent. On Windows, a service-session runner discovers the active logged-in desktop and launches the self-driving client there with Windows session APIs; no keyboard, mouse, menu navigation, or manual client launch is part of the test. The scenario list in `tools/live_join_test.py` generates both the CI matrix and the receipt requirements, preventing the gate from silently omitting a new scenario.
Missing, extra and stale receipts fail verification. CI cancels superseded PR
runs and keeps independent cells running after another cell fails.

Like the Immersive Portals verification approach, core checks stay cheap,
expensive checks have explicit evidence requirements, and diagnostics survive
failures. Documentation-only PRs skip live boots through a narrow allowlist;
build scripts, workflow edits, harness edits and unknown paths require the full
matrix. Manual and release runs always require all scenarios.

## Navigation regressions

One small region tests the lower and upper logical edges, both packed-Y edges,
section/page seams and a dense-world control. Each case requires an exact path
endpoint, valid node heights, walking up and down a step, an unreachable sealed
destination, airborne walking rejection, illegal-target rejection and a flying
path. Sparse empty-column normalization has a five-second watchdog; this catches
accidental scans toward a world boundary millions of blocks away. Per-case and
total timings are recorded in the server log.

The walking fixture explicitly establishes `onGround`: assigning a position
does not simulate a landing. Navigation regions expose logical bounds without
enlarging dense chunk arrays. Target normalization stays within the navigator's
local search radius.

## Client assertions and limits

Placement and breaking use the real client game mode, server packets and normal
prediction reconciliation. The client waits for separate server acknowledgements
before progressing; observing its own optimistic block state is insufficient.
The authoritative server always requires the true packed-Y alias canary to remain
unchanged. The client checks the same canary when it lies in the dense core; at the
full envelope that alias can itself be an unloaded sparse page, so pretending it
must be client-visible would be a false test. The upper client also leaves a real
player-placed stone in sparse storage; the server flushes,
evicts and reloads that page before the gameplay scenario can pass. A separate
core regression requires the loader-global page sender to survive a same-JVM
integrated-server stop/reopen lifecycle.

Render markers prove the camera-following view area, sparse render-chunk lookup,
and vanilla `LevelRenderer` neighbor graph all traversed the target high-Y section.
They **do not certify final pixels,
shader compatibility, Sodium/Embeddium, every third-party mod, or sustained
performance under a large player-built world**. Those require dedicated graphical
and workload coverage before making those release claims.

The modern rejoin scenarios also require `ENDLESS_VISIBLE_STONE_MESH_PASS`:
ordinary stone and its deepslate support in separate sections must have solid
geometry in the renderer's actual frustum-visible section list. Both run at
render distance 12. The support section has no block entities, preventing a
special block-entity rebuild from masking missing ordinary terrain rendering.
This catches the 26.1.2 visibility-tree regression: at render distance >= 8,
vanilla anchored the tree at the dense world bottom while Endless's section grid
followed the high-Y camera. Traversal and even forced compilation could pass
while the tree culled the geometry. The tree now follows the camera as well.
These checks verify drawable geometry and visibility selection, not final pixels.

Page updates dirty only the affected page and neighboring sections instead of
recreating the entire renderer. The live harness uses a four-chunk view distance
and bounded startup/gameplay deadlines. Across the live placement probes, an
acknowledged AIR observation gets 40 client ticks to settle before another
placement is attempted, up to three attempts. Each attempt and the subsequent
break require fresh prediction acknowledgements; persistent rejection still
fails. The harness never retries a failed assertion or weakens required markers. Only a transport/startup failure before any Endless verification progress can receive one retry; a phase-B retry preserves the saved phase-A world.

## Create review regressions

Both `create-compat` lanes require `ENDLESS_CREATE_ROTATION_SYNC_PASS` from the real graphical client. The fixture creates stopped Flywheel visuals for three 200-block vertical shaft lines: Y=207..406 crossing the dense-height boundary, and positive/negative million-scale Y controls. It sends native block-entity packets through `ClientPacketListener` to start at 16 RPM, reverse to -32, stop, and restart at 64. Every shaft must have matching block-entity speed and actual Flywheel instance rotation speed, with a common phase along each line. The test never queues visual updates itself after the initial stopped visual; Create's native client packet callback must refresh the instances. Client-only fixtures run in the disposable live-test world and are removed on success. This checks the data supplied to the instanced renderer, rather than claiming pixel-level image verification or testing server propagation in this particular fixture; the existing server kinetic fixture separately covers propagation.

Persistent client placement uses the same bounded acknowledgement/settlement checks as the breakable placement fixture. An acknowledgement arriving before the sparse page update does not immediately trigger a retry. If a delayed authoritative stone update arrives after a retry decision but before redispatch, the prior acknowledged attempt is accepted; a new dispatch still requires its own acknowledgement. Persistent air still fails after three attempts, and unexpected blocks are never overwritten to force a pass.

The Create cold-restart gate first saves two isolated generators whose positions alias under `BlockPos.asLong()`, plus an unused sentinel allocation. Phase A leaves the synthetic network IDs intact in block-entity NBT and writes an independent three-ID checkpoint. The harness makes a disposable copy of that world, removes only the allocator, starts a fresh phase-B JVM, and requires `ENDLESS_CREATE_PERSISTENCE_MISMATCH`. An unrelated crash, timeout, transport error, or successful phase B fails the negative control. The original world is restored in `finally` before the real phase-B launch. Logs for this expected failure are kept under `create-cold-restart-missing-allocator/phase-B`; they are not a passing production launch or a release receipt.

Only after positive phase-B identities match the independent checkpoint does the server run separate legacy-NBT fixtures. A real creative motor, shaft, mechanical press, and second overpowered generator are restored from saved NBT in root-first, follower-first, late-follower, and partially loaded late-follower orders. Before restoring downstream consumers/generators, the two partial-load fixtures compare getSize(), calculateStress(), and calculateCapacity() against the saved Network totals. A separate restored follower has an intentionally unavailable source chunk and a legacy root ID that would collide with its live modern owner under the previous Y-gap encoding; a real-API negative control first assigns that old ID to the owner and must reproduce the wrong-network admission. Restoring the modern ID and reconstructing the same follower must keep membership and both networks' totals separate. Two already-loaded disconnected legacy roots sharing the same packed ID must also migrate to separate network objects without stealing each other's membership. Assertions require identical network objects, complete member/source maps, exact nonzero stress and capacity, and propagated overstress/recovery. Numeric ID uniqueness or nonzero RPM alone cannot pass. Required final markers include `ENDLESS_CREATE_MIGRATION_PASS`, `ENDLESS_CREATE_PARTIAL_MIGRATION_PASS`, `ENDLESS_CREATE_UNAVAILABLE_SOURCE_PASS`, `ENDLESS_CREATE_ALIASED_LEGACY_ROOTS_PASS`, `ENDLESS_CREATE_ALLOCATOR_FAIL_CLOSED_PASS`, `ENDLESS_CREATE_CONTRAPTION_2047_CONTROL_PASS` and `ENDLESS_CREATE_CONTRAPTION_EXACT_POSITION_PASS`, `ENDLESS_CREATE_EXPANDED_MACHINES_PASS` before `ENDLESS_COLD_RESTART_PHASE_B_PASS`. Phase-specific marker requirements are retained in `result.json`; missing exact-position or machine markers fail the lane.

A marked interrupted-save fixture restores a follower with stable ID S and `EndlessLegacyNetworkId=L` while its direct source chunk remains unloaded. Its newer snapshot contains size 3, stress 256, and extra unloaded capacity; the stale root later restores L with size 2 and stress 128. Both initialize-first and tick-first admissions must preserve the newer snapshot through 260 validation ticks, another pending NBT save/reload, root migration, and repeated initialization. Exact network objects, membership, totals, marker cleanup, and absence of source chunk loading are asserted. Both cold-restart lanes require `ENDLESS_CREATE_MARKED_INTERRUPTED_SAVE_PASS`; omitting it fails the harness.

Allocator probes write duplicate IDs, missing fields, wrongly typed lists, unsafe earlier draft namespaces (including version-2 sparse provisional records with and without the role flag), and truncated compressed NBT to disk, then call the production allocator through `DimensionDataStorage.computeIfAbsent()`, including a repeat after its cached-null read. Allocation must fail and saving must not overwrite the corrupt file. New-file and valid-file controls must succeed. A Unix-only filesystem regression also rejects dangling allocator symlinks without overwriting them; Windows skips this privilege-dependent case. `testAllVersions` runs the 1.21.1 storage test in NeoForge's supported JUnit environment: NeoForge permits a custom SavedData factory without vanilla DFU, whereas Loom's vanilla common-test classpath does not. The live probe repeats the checks in the actual dedicated loader JVM on both Create targets.

The kinetic fix follows the identical `KineticNetwork.initFromTE/addSilently` and `TorquePropagator.networks` implementations at Create [6.0.8 commit 1a1a9a2](https://github.com/Creators-of-Create/Create/tree/1a1a9a2819b4f89f78caec41b55ed8cb222fa24b) and [6.0.11 commit fc9535d](https://github.com/Creators-of-Create/Create/tree/fc9535d82a29419164a1e9dc9c678bdcddeab30d). Migration seeds a new branch from the root's saved aggregate and transfers only members whose exact loaded Source chain reaches that root; a shared legacy Long key alone is not evidence of physical connectivity. The saved admission path runs before tick's pre-initialize propagation and also handles propagation admitting a follower before its own first tick. Normal Create initialization then sees an already admitted member and does not subtract twice.

Locally decompiled Forge 1.20.1 and NeoForge 1.21.1 `Level.isInWorldBoundsHorizontal` both permit X/Z in `[-30,000,000,30,000,000)`; `BlockPos` packs 26 X bits above 38 Z/Y bits. Namespace version 3 fixes packed X to 30,000,000 and uses the low 38 bits as the sequence, proving disjointness for all legal sparse and dense Y values. Version 3 records generator and provisional-follower roles separately. Version-2 draft files are rejected even when some records have `Follower=true`: earlier version-2 files omitted that flag for sparse provisional followers, making generator ownership ambiguous. Sequence exhaustion and old unversioned draft allocator files also fail closed; no allocator file is reset or removed.

Ejector JUnit regressions exercise all hit faces, wrench/placement modes, world identity, reset/MISS, and effective placement-cell aliases where even the raw hit positions do not alias. Released-world references also invalidate cache context without retaining the previous client world; the lifetime regression clears weak references deterministically instead of relying on GC timing. These are full-coordinate cache-key tests, not captured final GUI trajectory images. Complete display output and redstone-frequency copy workflows, other contraption assembly origins and enlarged/imported cases, complete Flywheel lighting/culling, and packaged production-launch testing remain separate verification scopes; the default mounted case and additional exact local-key payload checks are documented below.

Ordinary and high-Y clients report local PASS without disconnecting. The harness shuts them down only after all required client and server markers are present, and fails immediately on an early client exit/disconnect while evidence is missing. The server cold-restart fixture also explicitly rejects a disconnect after it starts. Completion budgets after the join outcome are scenario-specific: native Create gameplay groups receive `max(--timeout, 1800)` seconds, player/train workflows receive `max(--timeout, 600)` seconds, and other dedicated-server workflows receive `min(--timeout, 90)` seconds. Server readiness and the initial join outcome each use `--timeout`; these are separate sequential budgets, not a single overall deadline. Integrated rejoin uses a minimum 600-second completion budget. Native fixture assertion timeouts are separate (the sand-washing fixture uses 1,000 ticks to cover normal fan processing and belt travel). Completed assertion failures are not retried; the documented one-time transient pre-login transport retry remains available. CI also imposes its workflow job timeout.

The Forge build job also reads the shipped jar's refmap and requires the Minecraft invocation/constructor selectors used by the Create arm, pulley, elevator, contraption, train and chorus redirects to have their 1.20.1 runtime SRG mappings. An absent mapping or a development-only named selector fails the artifact gate. This verifies packaged mapping metadata; it does not replace a production launch.

### Exact contraption coordinates and expanded machine lifecycles

`LiveCreateContraptionSerializationTest` runs in positive phase B on both Create targets. At unchanged `maxBlocksMoved=2048`, real mounted assembly, entity storage initialization, disk NBT, and spawn NBT must preserve every block key/state for 2,047- and 2,048-payload columns. The latter has 2,049 entries including the anchor and local Y=+2048. A stripped-extension 2,047 control verifies legacy reading. `ENDLESS_CREATE_CONTRAPTION_EXACT_POSITION_PASS` replaces the former known-defect characterization; the harness no longer accepts a shifted payload. Unit tests also cover negative/positive envelope edges, packed aliases, and malformed or unknown-schema refusal.

`LiveCreateExpandedMachinesTest` exercises eight boundaries: -64, 320, -512, 512, -2048, 2048, -1,000,448, and 1,000,448. Real vertical motor/shaft/fan networks must preserve exact network identity, membership, nonzero stress/capacity, and overstress/recovery after both root-first and follower-first NBT reconstruction and first-tick ordering. Saved entities are all installed before ticking, matching native page/chunk loading; ticking between installations would create empty source block entities and model removal rather than saved reload. Network size must also match the four loaded members with no duplicate unloaded contribution. Real bearings, pistons, and pulleys assemble with a mounted chest holding seven diamonds and a drill actor. Full entity NBT must retain exact contraption data and position; the original entity is discarded. The restored entity must discover its native controller, resume movement/actor ticks, and disassemble without losing or duplicating blocks, inventory or actors. Bearing payloads span both sides of each boundary. Real display reservations survive NBT reload and distinguish packed aliases; relocated redstone links must invoke their native received-signal callback. Pulley limits retain Create's configured rope cap. Elevator contact discovery includes evicted persisted pages and must remain proportional to allocated pages. These are disposable dedicated-server fixtures and native controller ticks, not a certification of multiplayer rendering or passenger/collision physics.

Both graphical `create-compat` lanes additionally require `ENDLESS_CREATE_CLIENT_INTERACTION_PASS`. At eight expanded/boundary heights, real client arms initialize depot inputs and the native wrench hover handler distinguishes arms 4,096 blocks apart. The native ejector handler must select each aliased ejector's actual target. These local fixtures cover initialization/selection APIs. A separate required survival workflow uses real game-mode right clicks to select one depot input, one ejector output and the ejector destination; native Create handlers send arm/ejector settings packets over the real connection. Selection uses server-supplied hotbar items, normal carried-slot and sneak packets, side-face clicks within survival reach and a separate standing platform. The source depot receives the payload only after both settings are confirmed. The authoritative server must observe exact points, an arm-held stack, an in-flight ejector stack and all seven diamonds arriving at the target depot on normal ticks. Two positions 4,096 blocks apart require independent server acknowledgements and empty source, arm, ejector and flight inventories after delivery; the player must not collect any payload diamonds. Both client and server survival markers are mandatory. This does not certify pixel-level preview rendering.

## Run locally

From the repository root (use `gradlew.bat` on Windows):

```sh
./gradlew testAllVersions --no-daemon
./gradlew :fabric-1.20.1:build :forge-1.20.1:build --no-daemon
python -m unittest discover -s tools -p 'test_*.py' -v
python tools/live_join_test.py --target fabric-1.20.1 --scenario extended-server
python tools/live_join_test.py --target forge-1.20.1 --scenario million-gameplay
python tools/live_join_test.py --target forge-1.20.1 --scenario full-envelope-gameplay
python tools/live_join_test.py --target forge-1.20.1 --scenario create-cold-restart
python tools/live_join_test.py --target neoforge-1.21.1 --scenario create-cold-restart
python tools/live_join_test.py
```

The last command runs the complete live matrix sequentially in one checkout.
CI distributes cells across separate runners. A checkout lock prevents another
harness invocation from deleting a running scenario's world. Minecraft clients
need a graphical Windows session or Xvfb on Linux.

Evidence is under `build/live-join-evidence/<target>/<scenario>/`: process output,
game logs, crash reports and `result.json` containing the tested commit, whether
the checkout was modified, elapsed time, required markers and pass/fail status.
Cold-restart process logs retain separate `phase-A` and `phase-B` directories.
Modified-checkout results are useful locally but are not exact-commit release
receipts. CI uploads core and live diagnostics even on failure.

### PR #18 source-review regressions

Both Create cold-restart lanes require `ENDLESS_CREATE_POSITION_CODECS_PASS` from native `TrackNodeLocation.send/receive` after actual loader transformation. Cases include both signed-short edges, the escape sentinel, positive/negative million and +/-8M heights, nonzero pixel offsets, dimensions, native ordinary-node bytes and a following-payload canary. Native disk NBT is also checked. Native `AssemblyException.write/read` must preserve exact positions and components while stripped-extension legacy saves retain their original semantics.

`ENDLESS_CREATE_CHORUS_TELEPORT_PASS` requires Create's native action to sample all 16 rejected destinations within logical bounds, including both logical edges. Real cows must teleport successfully onto positive and negative million-height floors. Vanilla `LivingEntity.randomTeleport` formerly stopped at the dense minimum; its active logical-world landing search now visits allocated page intervals plus the dense core and retains the native fractional Y, collision/liquid rejection, particle event and navigation stop. Empty million-height gaps must finish within five seconds.

Phase A requires `ENDLESS_CREATE_MOVING_RESTART_PREPARED`: six real world entities (bearing, piston and pulley at positive and negative million heights), controller blocks, mounted chest contents and drill actors are saved by the server. After demonstrating motion, native NEVER_PLACE mode and zero motor RPM park the assemblies during server startup without disassembling them. An independent checkpoint records entity UUIDs, exact entity positions and payloads. Phase B powers the restored motors through their native speed control before requiring resumed movement. Phase B must retrieve the world-loaded entities by those same UUIDs, discover their restored controllers, compare exact blocks, mounted storage and actor positions, resume native movement and disassemble those restored entities with exactly one chest, seven diamonds and one drill each. `ENDLESS_CREATE_MOVING_RESTART_PASS` is required; creating a new phase-B fixture cannot satisfy it.

The graphical lanes require `ENDLESS_CREATE_DESTRUCTION_POSITIONS_PASS`. Native water-wheel structural crack events must create separate primary and extra entries for simultaneous positions 4,096 blocks apart, preserve independent stages, reconstruct exact render positions and remove only the intended breaker. Updating, retargeting onto a vanilla block, cancelling and coordinate-key cleanup are checked. World replacement clears the renderer-local key map; unit coverage checks mutable-key snapshots and clear/reuse behavior.

The 1.21.1 navigation gate also caches an open sparse cell, closes it, reopens it through the sparse page writer, and closes it again without a vanilla update callback. The native cache must report each current state and navigation must refuse the sealed destination. Sparse writes invalidate the native cache even before the dense chunk reaches BLOCK_TICKING.

These coordinate and migration gates are complemented by the required native gameplay and lifecycle fixtures below. They still do not establish every train configuration or pixel-level Flywheel lighting/culling. Dedicated native train travel and client graph checks are required separately below. No seamless-all-features claim is made.

## Pinned Create gameplay outcomes

The required `create-gameplay-{contraptions,fluids,items,misc,processing}` cells run on Forge 1.20.1 / Create 6.0.8-289 and NeoForge 1.21.1 / Create 6.0.11-312. NeoForge also runs `create-gameplay-regressions`, which is absent from the pinned Forge version. The exact registered native methods are recorded in `tools/create-gameplay-catalog.json`; runtime discovery must match that catalog, so an empty or incomplete native collection cannot pass.

Every native test is repeated at origins 64, -67, 317, -515, 509, -2051, 2045, -1,000,451 and +1,000,445. Native fixture blocks span the normal-height control, both vanilla boundaries, both signs of sparse-page seams, packed-Y edges and million-scale heights. Four individual function/height cases run concurrently in isolated horizontal cells. The disposable world uses the native GameTest flat-world seed and game rules. Real glowstone walls outside each template supply crop-survival light at buried origins. Fixture cleanup removes ejected entities throughout the reused horizontal cell, preventing items from a previous height contaminating another case. Structures, entities, recipes, lever interactions, world ticks and outcome assertions come from the pinned Create jar. Only the runner's test scheduling is driven separately; it does not inject recipe results, inventories, actor results or motion. A failed native assertion or missing group marker fails the CI cell. Completion time is extended for these gameplay batches without changing the existing join/runtime gates.

The native suites provide 540 Forge and 576 NeoForge observable outcomes: water wheels and steam generation, pipe/pump/valve/hose/spout fluid workflows, belts/funnels/tunnels/chutes/vaults and arm transfers, pressing/mixing/crushing/milling/washing/mechanical crafting/sequenced assembly, moving farming/ploughing/rollers/dispensers/storage interfaces, elevator travel with a cow passenger, observers/switches/nixies/display output, actual schematic capture/cannon construction and netherite backtank protection. The NeoForge regression additionally checks deployers completing a real placement result. The upstream disabled train-observer test remains excluded because it is not a registered test; the separate train workflow below exercises registered native station, signal and schedule behavior.

The cold-restart lanes additionally require `ENDLESS_CREATE_FACTORY_RESTART_PREPARED` and `ENDLESS_CREATE_FACTORY_RESTART_PASS`. Nine fixtures build a survival windmill with nine native wool sails, a shaft/gearshift/clutch/large-to-small cog train, and redstone-driven packaging of seven diamonds. Phase A saves actual world entities and a package during animation. Phase B loads the same windmill UUID and exact native contraption blocks/anchor/sails/seats/passengers, observes continued rotation and cog ratios on natural server ticks, then uses real redstone updates and scheduled block ticks to check clutch disconnection/recovery and gearshift reversal. A saved pig passenger must retain the same vehicle UUID, follow its transformed seat position on world ticks and dismount near the correct full-height position. Native package inventory extraction/insertion must deliver exactly seven diamonds after restart with no remaining source items or held package.

These are feature outcome checks, not a claim that every registered block, equipment interaction or logistics path has been exercised. Remaining dedicated workflows include fresh-restart train scheduling, every glue/chassis/sticker arrangement and active unload/revisit combinations. Passing these fixtures cannot certify every feature variant. Source-confirmed failures found by these required tests are fixed without weakening the native assertions. The smart-observer fixture caught vanilla pistons using dense min/max heights in `isPushable`; the fix redirects only those boundary reads to logical limits, retaining native obstacle, border, reaction and block-entity rules.


Both `create-player-workflows` cells require client and authoritative-server completion. Nine normal/boundary/million-height lanes use actual survival game-mode clicks and native Create packets to link and unlink a toolbox (exactly 64 iron conserved), place a copycat material and scaffolding with item costs, configure a symmetry wand and observe mirrored wool with inventory cost, reject an ordinary out-of-reach placement and then accept the same placement with an extendo grip (including durability), and remain underwater with copper diving equipment using the native backtank air component. The underwater baseline must first consume player air without equipment; equipped natural ticks must retain full player air while consuming exactly three units of tank air. After native stock-link registration, a real client stock-ticker packet orders seven diamonds from fourteen available. A real redstone edge then orders the remaining seven through a requester. Both native packages must retain the exact address and contents and unpack to fourteen diamonds, with an empty source and a zero native stock summary.

Cold restart also requires addressed chain/frogport delivery (`ENDLESS_CREATE_LOGISTICS_RESTART_PREPARED/PASS`), translating drill work (`ENDLESS_CREATE_GANTRY_RESTART_PREPARED/PASS`) and both clockwork hands (`ENDLESS_CREATE_CLOCKWORK_RESTART_PREPARED/PASS`). These use the same nine seams and save real world entities/block entities in phase A. Frogports save while exporting; normal phase-B ticks must deliver one package containing seven diamonds through a connected conveyor crossing the seam, reject a wrong-address receiver and leave no duplicate inventory or in-flight package. Gantries must resume the saved entity, mine three actual stone blocks with the native moving drill, conserve seven mounted diamonds and collect exactly three cobblestone and three cut oak logs using an attached horizontal moving saw, then disassemble at the translated anchor with the exact inventory. Both clockwork hand UUIDs and payloads must survive, reattach to the native controller, follow a changed world-time target and disassemble to exact original positions.

The native belt-coaster outcome exposed `LevelReader.hasChunksAt` rejecting logical-height entities because of its dense Y guard, preventing funnel `entityInside` callbacks. The fix preserves the native horizontal chunk check and uses logical Y limits while Endless is active. The survival symmetry test also exposed an unannotated client-renderer signature in Create's mirror data classes during NeoForge IDE component validation on a dedicated server. The NeoForge-only mixin plugin removes that renderer method on dedicated servers; native component validation, value equality and mirror geometry remain intact, and client rendering retains the method.


Some pinned upstream fixtures themselves contain obsolete or nondeterministic setup. NeoForge depot inventories retain legacy `Count` bytes despite declaring a current structure data version; the test runner translates these to the current `count` field before placement, retaining the exact original input quantity. Legacy threshold-switch templates store percentages while current Create expects absolute amounts; the runner uses native default amounts during initial settling, then restores the original percentages against the native observed minimum and maximum before the test starts. Precision-mechanism crafting requires both success and a random byproduct from sixteen inputs; fan processing also requires chance-based washing output. These cases run separately and seed their pinned native random source (`Create.RANDOM` for Forge sequenced result selection and private `ProcessingOutput.r` for Forge chance outputs; the level random source on NeoForge). The open-pipe effect fixture disables AI wandering of its spawned zombies while retaining gravity, seating, fluid effects and native assertions. Recipe/result-pool logic and native outcome assertions are unchanged. Freshly placed native hose-pulley templates clear only captured absolute search-context keys (`LastPos`, `AffectedAreaFrom/To`, `Infinite`); otherwise the drainer queues an unrelated capture-world chunk and can wait indefinitely before rebuilding at its relocated root. Offset, speed, tank inputs, native fluid outcomes and actual cold-restart NBT are retained. Native passenger assertions sample the previous seat transform because vanilla ticks passengers before the bearing block entity, including Create's entity-specific seating offset.

The two `create-train-workflows` cells require `ENDLESS_CREATE_TRAIN_SERVER_PASS` and `ENDLESS_CREATE_TRAIN_CLIENT_PASS`. At nine normal, seam, packed-Y and million heights, native stations assemble a bogey, controls, blaze conductor, passenger seat and seven mounted diamonds. A real survival player rides while a native conductor schedule travels over forty blocks to the destination station. Native signals must report green before travel, red while the destination segment is occupied, and green after disassembly. The client must ride at least sixteen blocks and receive a nonempty native train graph whose every node retains the exact track Y. A real client button-use packet acknowledges the observed ride before server disassembly and lane advancement. Native disassembly must restore the exact seven diamonds at the destination. This is an actual client/server train ride, beyond packet-code round trips.

The survival stock workflow additionally rejects premature output from one incomplete package fragment, then requires the native powered repackager to combine two fragments containing three and four diamonds, preserve their order ID and full-height address, and unpack exactly seven additional diamonds without retaining source fragments or duplicate queued packages. Natural mob spawning is disabled in the disposable player/train test worlds; their survival game mode and native item costs, air use, reach and gameplay interactions remain active.

The NeoForge potion-processing fixture exposed immediate sparse block-entity packet serialization changing Create belt inventories during a native iterator. Sparse `ChunkHolder` updates now retain immutable positions and event recipients, coalesce repeated positions, and serialize after all server world ticks, after active machine callbacks. Delivery rechecks the recipient world; teleporting to a different page does not erase an acknowledgement already owed to that player.

Cold restart requires `ENDLESS_CREATE_CART_RESTART_PREPARED/PASS`: nine pairs of real cargo minecarts must consume one coupling item in survival, reject a duplicate coupling, preserve both entity UUIDs and reciprocal native controllers across a fresh JVM, move on real powered rails while retaining their spacing, and conserve seven diamonds plus three iron ingots without extra occupied slots. The rails are vanilla minecart rails; the separate train lanes exercise Create tracks, graphs, stations, signals and conductor schedules.

The survival player lanes require a real factory gauge to discover its attached packager, reject absent stock without phantom promises, restock seven diamonds from the linked network and observe exact delivered demand. Train lanes also require addressed mail to leave a source postbox, ride in the mounted inventory, arrive at the destination postbox with seven emeralds, and leave a wrong-address postbox empty.

Sparse block-entity updates are queued during world mutations and flushed after the server tick. Native Create belt serialization must not execute inside its inventory iterator. The required client/server survival acknowledgements cover later sparse updates as well as initial page snapshots. NeoForge cart attachments restore with their actual owning cart, retaining Create's native constructor and NBT reader so controllers register without waiting for a player-tracking callback.

Both Create compatibility lanes require `ENDLESS_CREATE_SOUND_POSITIONS_PASS`. Native SoundPool callbacks retain full positions at nine heights, including aliased entries 4,096 blocks apart, mutable position snapshots, duplicates, explicit packed callers, merge delay, queue reuse and native random/concurrency limits. Unmodified BoilerData callbacks must reach Minecraft SoundManager with exact hiss coordinates and centered steam coordinates. This validates playback submissions; it does not measure audible output or claim a complete survival engine lifecycle.

Cold-restart sessions also wait up to 60 seconds for the native `session.lock` file/range lock to release after stopping the launcher. A launcher exit alone does not prove its game JVM has finished saving. Snapshotting the negative-control world, restoring it and starting phase B occur only after this shutdown check; a held lock fails the test.

The cargo-cart restart fixture forces each new chunk once, verifies that those tickets survived on disk and waits for natural entity ticks before checking Create controller registration or starting powered travel. Repeated vanilla `setChunkForced` calls for an already-forced chunk clear the saved-data dirty flag; fixture setup must not accidentally discard its newly added tickets. Travel, reciprocal coupling, full height and exact cargo assertions remain required.
