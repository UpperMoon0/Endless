# Minecraft 1.21.1 dedicated-server comparison

Baseline: `67b991ec10e21a1747179dedb8d0704d72f914ac`, the default branch pulled for
this investigation (Endless 0.9.4). Candidate: the 0.9.5 performance changes in
this PR. The candidate's exact binary is identified in `metadata.json`.

## Method

- Official vanilla, Fabric without Endless, current Endless, and candidate
  Endless use the same Java 21 runtime and measurement agent.
- Fabric Loader 0.17.2, Fabric API 0.116.5+1.21.1, and Architectury 13.0.8 are
  identical in the three Fabric variants. No optional gameplay or renderer mods
  are installed in these server fixtures.
- Every variant uses five fresh JVMs and fresh flat worlds, seed 0, a 1 GiB
  initial / 2 GiB maximum heap, 64 forced chunks, and view/simulation distance 4.
  Natural spawning, random ticks, weather changes and daylight progression are
  disabled equally. There are no connected players.
- Endless retains its full sparse features with logical bounds
  `[-1,048,576, 1,048,576)` and the vanilla dense core. Dense fixtures edit Y=80;
  sparse fixtures edit Y=4096. Vanilla cannot supply the sparse-height control.
- Each workload has 500 warmup and 1,000 measured unlimited-speed ticks. Idle
  keeps the initial 2,048-block fixture. Reads perform 256 native
  `/execute positioned over motion_blocking` height queries per tick. Edits
  alternate a 32x2x32 volume between stone and air, then run the height queries.
- Round order reverses on alternate rounds. Each launch owns new paths; no
  existing world is reset. Native fills, query counts, high-Y writes, sparse
  persistence, exact warmup counts, and exact measured counts are checked.
- The agent measures `MinecraftServer.tickServer`; it tags native sprint ticks
  directly, avoiding ambiguous wall-clock boundaries and command-dispatch ticks.
  Arrays hold measurements until shutdown, so CSV I/O does not run inside ticks.
- The helper is bootstrap-visible. Fabric's classpath isolation is disabled
  only in these measurement JVMs so it can resolve the same hook as Mojang's
  bundler. Benchmark agents are never packaged into the mod.

The host is an Intel Core i3-8100 at 3.60 GHz, four logical CPUs, 16 GiB RAM,
Windows, and JetBrains Java 21.0.11. Local build and gameplay-test JVMs were
closed before the final series. The host was not otherwise exclusive; an
unrelated Minecraft process remained running. Raw run ranges are retained.

`summary.md` shows medians of independent JVM summaries rather than pooling
ticks. `summary.json` also keeps per-JVM ranges. CPU and allocation cover the
server thread, not workers or the whole process. Windows CPU counters are
coarse, so use aggregate CPU means; per-tick CPU percentiles are not meaningful.
Timings are observations for these fixtures, not a guarantee for every world.

## Scope and correctness

These measurements do not establish client FPS, renderer throughput, world
generation, multiplayer/page-send behavior, GC pause distributions, peak RAM,
or speedups on other Minecraft versions. The full unit, packaging, required live
gameplay and renderer matrix remains the feature-preservation gate. Optimizations
retain height predicates, empty-column caching, full X/Z identity, cache lifecycle,
lighting invalidation, native delegation and all sparse storage/network features.

The first unmerged cache implementation showed read-query/allocation overhead
from packed chunk-key hash collisions. That calibration series was rejected.
The candidate measured here mixes full chunk keys bijectively before hashing;
the first candidate's measurements are not pooled into this report.

## Reproduce

Freeze an official Minecraft 1.21.1 server jar, the Fabric 1.21.1/0.17.2 server
launcher, matching Fabric API and Architectury jars, a clean build of baseline
`67b991e`, and the candidate jar. Record their hashes. The vanilla jar used here
matches Mojang's server SHA-1 `59353fb40c36d304f2035d51e7d6e6baa98dc05c`.

Run from the repository root with a new output directory:

```text
python tools/benchmark_server.py --java JAVA21_DIRECTORY --vanilla VANILLA_SERVER.jar --fabric FABRIC_SERVER_LAUNCHER.jar --current BASELINE_ENDLESS.jar --candidate CANDIDATE_ENDLESS.jar --dependency FABRIC_API.jar --dependency ARCHITECTURY.jar --output build/performance-run --labels current candidate vanilla fabric --repeats 5 --warmup 500 --ticks 1000
python tools/summarize_server_benchmark.py build/performance-run
```

The runner writes binary hashes, JVM/host information, full tick CSVs, per-JVM
results and scenario windows. This directory retains compressed raw tick CSVs,
all per-JVM results and the aggregate report for review.
