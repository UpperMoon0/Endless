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
| Representation envelope | Sparse access, POIs and page packet/storage round trips near ±8,000,000 | Lightweight smoke, no full-height scans |
| Cold restart | Blocks, fluids, block entities, redstone, light and POIs near ±8,000,000 after a fresh server JVM | Two launches reuse only that scenario's saved world |
| Same-JVM rejoin | Automated save/close/reopen in one client JVM on both a migrated legacy Y=1,000,000 save and a fresh +/-8M world at Y=6,000,000; generic block-entity registration, completed render-section membership and visible ordinary solid meshes at distance 12; dense canary preservation | Shell/CI launches the self-driving graphical client; no manual navigation |
| Create compatibility | Pinned Forge 1.20.1 / Create 6.0.8 and NeoForge 1.21.1 / Create 6.0.11: rails, kinetic identity, independent fresh-JVM persistence, connected legacy migration, corrupt-storage refusal, and a default mounted-contraption serialization limitation characterization | Two existing scenarios per supported Create target; extra probes stay inside cold restart |
| Compatibility baselines | Vanilla-range Endless server and genuine vanilla server, including stale client range reset | No gameplay compatibility dependencies |

The live matrix contains **29 required cells**: 16 Fabric/Forge 1.20.1 cells, four Create compatibility/cold-restart cells, three port-runtime cells for Fabric 1.21.1 / NeoForge 1.21.1 / NeoForge 26.1.2, and six same-JVM rejoin cells across all three modern targets. The rejoin pair per modern target covers both the migrated 254-section legacy dense layout at Y=1,000,000 and the exact fresh full-envelope regression at Y=6,000,000 with render distance 12. Each rejoin creates the world, installs multiple vanilla block-entity types, saves and shuts down the integrated server, waits for the exact server thread to release the world lock, reopens the same save in the same client JVM, and requires every block entity to appear in a completed render section without interaction. Block-entity evidence uses full XYZ coordinates rather than vanilla's packed `BlockPos.asLong()` representation. The dense canary must also survive and render again.

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

The Create cold-restart gate first saves two isolated generators whose positions alias under `BlockPos.asLong()`, plus an unused sentinel allocation. Phase A leaves the synthetic network IDs intact in block-entity NBT and writes an independent three-ID checkpoint. The harness makes a disposable copy of that world, removes only the allocator, starts a fresh phase-B JVM, and requires `ENDLESS_CREATE_PERSISTENCE_MISMATCH`. An unrelated crash, timeout, transport error, or successful phase B fails the negative control. The original world is restored in `finally` before the real phase-B launch. Logs for this expected failure are kept under `create-cold-restart-missing-allocator/phase-B`; they are not a passing production launch or a release receipt.

Only after positive phase-B identities match the independent checkpoint does the server run separate legacy-NBT fixtures. A real creative motor, shaft, mechanical press, and second overpowered generator are restored from saved NBT in root-first, follower-first, and late-follower orders. Assertions require identical network objects, complete member/source maps, exact nonzero stress and capacity, and propagated overstress/recovery. Numeric ID uniqueness or nonzero RPM alone cannot pass. Required final markers include `ENDLESS_CREATE_MIGRATION_PASS`, `ENDLESS_CREATE_ALLOCATOR_FAIL_CLOSED_PASS`, `ENDLESS_CREATE_CONTRAPTION_2047_CONTROL_PASS` and `ENDLESS_CREATE_DEFAULT_CONTRAPTION_LIMITATION_CONFIRMED` before `ENDLESS_COLD_RESTART_PHASE_B_PASS`. Phase-specific marker requirements and an explicit `known_limitations` entry are retained in `result.json`.

Allocator probes write duplicate IDs, missing fields, wrongly typed lists, and truncated compressed NBT to disk, then call the production allocator through `DimensionDataStorage.computeIfAbsent()`, including a repeat after its cached-null read. Allocation must fail and saving must not overwrite the corrupt file. New-file and valid-file controls must succeed. A Unix-only filesystem regression also rejects dangling allocator symlinks without overwriting them; Windows skips this privilege-dependent case. `testAllVersions` runs the 1.21.1 storage test in NeoForge's supported JUnit environment: NeoForge permits a custom SavedData factory without vanilla DFU, whereas Loom's vanilla common-test classpath does not. The live probe repeats the checks in the actual dedicated loader JVM on both Create targets.

Ejector JUnit regressions exercise all hit faces, wrench/placement modes, world identity, reset/MISS, and effective placement-cell aliases where even the raw hit positions do not alias. Released-world references also invalidate cache context without retaining the previous client world; the lifetime regression clears weak references deterministically instead of relying on GC timing. These are full-coordinate cache-key tests, not captured final GUI trajectory images. Display/redstone copy/relocation coverage, other contraption assembly origins and enlarged/imported cases, complete Flywheel lighting/culling, and packaged production-launch testing remain separate verification scopes; the confirmed default mounted case is documented and characterized below.

Ordinary and high-Y clients report local PASS without disconnecting. The harness shuts them down only after all required client and server markers are present, and fails immediately on an early client exit/disconnect while evidence is missing. The server cold-restart fixture also explicitly rejects a disconnect after it starts. Existing bounded deadlines remain unchanged; there is no assertion retry or longer-timeout workaround.

### Default mounted-contraption boundary: known limitation, not safety certification

Review #5350055270 disproved the earlier default-cap guarantee. `LiveCreateContraptionSerializationTest` is now called in the positive phase B of both existing `create-cold-restart` lanes, after independent identity persistence and migration checks. It reads and rechecks the unchanged live `maxBlocksMoved=2048` value; it never raises or overrides that configuration. No separate unchecked review agent is needed.

The fixture builds a cart assembler at `(0, 1001000, 14)` with a connected slime column, calls the real `MountedContraption.assemble`, initializes mounted storage with `OrientedContraptionEntity.create`, and round-trips `writeNBT(false)` through `Contraption.fromNBT(..., false)`. The 2,047-payload control has 2,048 entries including the anchor and must preserve every local block key and state. Adding the 2,048th payload block still assembles at the default cap, gives 2,049 entries, and must demonstrate exactly one shifted block: local `(0,+2048,0)` becomes `(0,-2048,0)` while all other blocks/states and the entry count are preserved. The test cleans its scratch column in `finally` and never disassembles corrupt data into the world or spawns the carrier.

The evidence marker is deliberately `ENDLESS_CREATE_DEFAULT_CONTRAPTION_LIMITATION_CONFIRMED`, with `knownLimitation=true`, `exactCoordinatesPreserved=false` and `serializerFixed=false`. This is a **known-limitation characterization**, not a skipped assertion and not a serializer fix. A missing marker, rejected setup, changed cap, control failure, or different serialization result fails the lane. If #14 introduces a guard or fixes serialization, update this fixture to require safe refusal or exact preservation and revise the limitation documentation together. Do not describe a green lane as proof that default-size contraptions are safe. The matrix still has 29 cells, with no relaxed existing checks or longer deadlines.

The signed packed local-Y representation is asymmetric (`-2048..2047`), and a payload origin offset matters independently of the block count. Issue [#14](https://github.com/UpperMoon0/Endless/issues/14) remains open; default mounted assembly, not just enlarged or imported contraptions, is part of its implementation scope. Runtime serializers and assembly guards are unchanged in this follow-up.

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
