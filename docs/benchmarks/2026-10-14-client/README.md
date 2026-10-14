# Minecraft 1.21.1 client FPS comparison

Baseline: Endless 0.10, default-branch commit `d94d7b7`. Candidate: 0.10.1.

The benchmark launches official production Minecraft clients in fresh JVMs, uses
an identical GLFW present hook in vanilla and Fabric, and connects each client
to a fresh local dedicated server. No user account files or existing worlds are used.
Raw frames, screenshots, profiles, hashes and host metadata remain under ignored
`build/` directories. The benchmark agent is never shipped in the mod.

Fixtures use a flat seed-0 world, 49 stone towers, 1280x720, render distance 8,
Fancy graphics, no shaders or optional renderers, VSync disabled and unlimited FPS.
Natural spawning, random ticks, daylight and weather progression are disabled.
A static view, scripted camera rotation, and repeated stone/glass edits are
measured separately. Dense Y=80 compares official vanilla, Fabric without Endless,
Endless 0.10 and 0.10.1. Sparse Y=4096 compares the two Endless versions; vanilla
cannot supply that height control. These fixtures do not establish FPS for a
populated modpack, shaders, world generation or every supported Minecraft version.

Frame intervals are measured between actual presents. Average FPS is total frames
divided by elapsed time; 1% low is the inverse mean interval of the slowest 1%.
Each workload excludes warmup and screenshots. Summaries use medians of independent
JVMs and keep frame p95, p99, worst frames and each workload visible.

## Reproduce

Use Java 21, public Prism component metadata and cached assets, the official
1.21.1 server jar, a Fabric 0.17.2 launcher, pinned Fabric API 0.116.5+1.21.1 and
Architectury 13.0.8, and frozen production Endless jars. All launch libraries
with published SHA-1 metadata are verified. Use a hardware display on Windows.

```text
python tools/benchmark_client.py --java JAVA21_DIRECTORY --cache PRISM_DIRECTORY --vanilla-server VANILLA_SERVER.jar --fabric-server FABRIC_SERVER.jar --current ENDLESS_0.10.jar --candidate ENDLESS_0.10.1.jar --dependency FABRIC_API.jar --dependency ARCHITECTURY.jar --output build/client-fps-run --repeats 3 --warmup 20 --seconds 20
python tools/summarize_client_benchmark.py build/client-fps-run
```

Close owned build/test JVMs before collecting the final series. Do not terminate
unrelated applications. Retain and disclose host contention and GPU information.
Inspect the native framebuffers to verify the intended scene rendered, including
edits at the sparse height. Calibration and profiled pilots are excluded from
reported comparison runs.

## Snapshot scope

Vanilla mesh workers capture sparse block palettes within 32 sections on either
side of the main camera, including the 32-section render window, its 8-section
rebase hysteresis and neighbor halo. The scan covers 65 sections regardless of
world height or previously visited pages. Queries outside the snapshot retain
the original live lookup, and block-entity discovery remains live. Copies add
bounded palette memory; this is not a claim of lower peak process memory.

The final comparison and validation are recorded in [PR #28](https://github.com/UpperMoon0/Endless/pull/28).
