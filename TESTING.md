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
| Compatibility baselines | Vanilla-range Endless server and genuine vanilla server, including stale client range reset | No gameplay compatibility dependencies |

Every live scenario runs on Fabric and Forge: **16 required cells**. The same-jvm-rejoin cell is fully automated: it creates/opens the singleplayer world, performs the million-height sparse edit, saves, shuts down the integrated server, reopens the same save in the same client JVM, returns to dense terrain, and verifies persistence/render state without manual menu navigation or clicks. The
scenario list in `tools/live_join_test.py` generates both the CI matrix and the
receipt requirements, preventing the gate from silently omitting a new scenario.
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

Page updates dirty only the affected page and neighboring sections instead of
recreating the entire renderer. The live harness uses a four-chunk view distance
and bounded startup/gameplay deadlines. It does not retry a failed assertion into
a pass or weaken required markers.

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
