# Endless verification

The release gate requires **Validate** and **Required live verification** to pass
on the same final commit. A build, a successful login, or a representation smoke
test alone does not certify real player interaction at large heights.

## Layers

| Layer | Coverage | Cost control |
| --- | --- | --- |
| Core | Both loader builds; JUnit storage, codec, config, logical/dense geometry and migration regressions | One Gradle build |
| Harness | Scenario selection, marker failures, commit receipts, concurrent-run exclusion and failure evidence | Python standard library, no game boots |
| Extended gameplay | Commands, boundaries, blocks/fluids/block entities, POIs, lighting, scheduled mechanics, persistence reload, Waystones, navigation, real client place/break and acknowledged prediction | Shared small fixtures at both edges |
| Million gameplay | The same complete gameplay assertions in `[-1,048,576, 1,048,576)` | Reuses the extended scenario; bounded X/Z and vertical windows |
| Representation envelope | Sparse access, POIs and page packet/storage round trips near ±8,000,000 | Lightweight smoke, no full-height scans |
| Cold restart | Blocks, fluids, block entities, redstone, light and POIs near ±8,000,000 after a fresh server JVM | Two launches reuse only that scenario's saved world |
| Same-JVM rejoin | Automated save/close/reopen in one client JVM on both a migrated legacy Y=1,000,000 save and a fresh +/-8M world at Y=6,000,000; generic block-entity registration, completed render-section membership and visible ordinary solid meshes at distance 12; dense canary preservation | Shell/CI launches the self-driving graphical client; no manual navigation |
| Compatibility baselines | Vanilla-range Endless server and genuine vanilla server, including stale client range reset | No gameplay compatibility dependencies |

The live matrix contains **25 required cells**: 16 Fabric/Forge 1.20.1 cells, three port-runtime cells for Fabric 1.21.1 / NeoForge 1.21.1 / NeoForge 26.1.2, and six same-JVM rejoin cells across all three modern targets. The rejoin pair per modern target covers both the migrated 254-section legacy dense layout at Y=1,000,000 and the exact fresh full-envelope regression at Y=6,000,000 with render distance 12. Each rejoin creates the world, installs multiple vanilla block-entity types, saves and shuts down the integrated server, waits for the exact server thread to release the world lock, reopens the same save in the same client JVM, and requires every block entity to appear in a completed render section without interaction. Block-entity evidence uses full XYZ coordinates rather than vanilla's packed `BlockPos.asLong()` representation. The dense canary must also survive and render again.

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
fails. The harness never reruns a failed scenario or weakens required markers.

## Run locally

From the repository root (use `gradlew.bat` on Windows):

```sh
./gradlew build --no-daemon
python -m unittest discover -s tools -p 'test_*.py' -v
python tools/live_join_test.py --target fabric-1.20.1 --scenario extended-server
python tools/live_join_test.py --target forge-1.20.1 --scenario million-gameplay
python tools/live_join_test.py --target forge-1.20.1 --scenario full-envelope-gameplay
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
