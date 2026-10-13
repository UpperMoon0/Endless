#!/usr/bin/env python3
"""Summarize independent fresh JVMs without pooling ticks or hiding workloads."""
import argparse
import json
import statistics
from pathlib import Path


def aggregate(results):
    groups = {}
    identities = set()
    for result in results:
        identity = (result["label"], result["sparse"], result["repeat"])
        if identity in identities:
            raise ValueError(f"Duplicate JVM result: {identity}")
        identities.add(identity)
        for workload, metrics in result["metrics"].items():
            groups.setdefault((result["sparse"], workload, result["label"]), []).append(metrics)
    records = []
    for (sparse, workload, label), measurements in sorted(groups.items()):
        record = {"sparse": sparse, "workload": workload, "label": label, "jvms": len(measurements)}
        for name, metric, summary, scale in (
            ("tick_median_ms", "tick_ns", "median", 1e6),
            ("tick_p95_ms", "tick_ns", "p95", 1e6),
            ("tick_p99_ms", "tick_ns", "p99", 1e6),
            ("cpu_mean_ms", "cpu_ns", "mean", 1e6),
            ("allocated_mean_kib", "allocated_bytes", "mean", 1024),
        ):
            values = [m[metric][summary] / scale for m in measurements]
            record[name] = statistics.median(values)
            record[name + "_range"] = [min(values), max(values)]
        records.append(record)
    return records


def markdown(records):
    lines = ["# Dedicated-server performance results", "",
        "Each cell is the median of the independent JVM summaries. Tick tails remain separate; CPU and allocation use per-JVM means.", "",
        "| Height | Workload | Variant | JVMs | Tick median ms | Tick p95 ms | CPU mean ms/tick | Allocation KiB/tick |",
        "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |"]
    for r in records:
        lines.append(f"| {'Y=4096' if r['sparse'] else 'Y=80'} | {r['workload']} | {r['label']} | {r['jvms']} | "
                     f"{r['tick_median_ms']:.4f} | {r['tick_p95_ms']:.4f} | {r['cpu_mean_ms']:.4f} | {r['allocated_mean_kib']:.2f} |")
    lines += ["", "## Candidate change against current Endless", "",
        "Negative percentages mean less time or allocation. These workloads are never combined into an overall score.", "",
        "| Height | Workload | Tick median | Tick p95 | CPU mean | Allocation mean |",
        "| --- | --- | ---: | ---: | ---: | ---: |"]
    lookup = {(r["sparse"], r["workload"], r["label"]): r for r in records}
    for sparse in (False, True):
        for workload in ("idle", "reads", "edits"):
            old = lookup.get((sparse, workload, "current"))
            new = lookup.get((sparse, workload, "candidate"))
            if not old or not new:
                continue
            changes = []
            for key in ("tick_median_ms", "tick_p95_ms", "cpu_mean_ms", "allocated_mean_kib"):
                changes.append(f"{(new[key] / old[key] - 1) * 100:+.1f}%" if old[key] else "n/a")
            lines.append(f"| {'Y=4096' if sparse else 'Y=80'} | {workload} | " + " | ".join(changes) + " |")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    results = json.loads((args.directory / "results.json").read_text())
    records = aggregate(results)
    (args.directory / "summary.json").write_text(json.dumps(records, indent=2))
    (args.directory / "summary.md").write_text(markdown(records))
    print(markdown(records))


if __name__ == "__main__":
    main()
