#!/usr/bin/env python3
"""Keep individual client workloads and tails visible; aggregate independent JVMs."""
import argparse
import json
import math
from pathlib import Path
import statistics
from benchmark_client import WORKLOADS


def identity(receipt):
    label, sparse, repeat = (receipt[key] for key in ('label', 'sparse', 'repeat'))
    if (label not in ('vanilla', 'fabric', 'current', 'candidate')
            or type(sparse) is not bool or type(repeat) is not int or repeat < 0
            or sparse and label in ('vanilla', 'fabric')):
        raise ValueError('Invalid JVM identity')
    return label, sparse, repeat


def summarize(results, metadata):
    plan = metadata.get('planned_runs', [])
    expected = {identity(entry) for entry in plan}
    if not expected or len(expected) != len(plan):
        raise ValueError('A nonempty, unique planned JVM matrix is required; legacy receipts cannot certify completeness')
    seconds = metadata['measured_seconds']
    if type(seconds) not in (int, float) or not math.isfinite(seconds) or seconds < 10:
        raise ValueError('Invalid planned measurement duration')
    groups = {}
    identities = set()
    for result in results:
        jvm = identity(result)
        if jvm in identities:
            raise ValueError('Duplicate JVM receipt')
        identities.add(jvm)
        if set(result['metrics']) != set(WORKLOADS):
            raise ValueError(f'Missing or unexpected workload in {jvm}')
        for workload, metrics in result['metrics'].items():
            for name in ('seconds', 'average_fps', 'one_percent_low_fps', 'median_ms', 'p95_ms', 'p99_ms', 'max_ms'):
                value = metrics[name]
                if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
                    raise ValueError(f'Invalid {name} in {jvm}/{workload}')
            if (type(metrics['frames']) is not int or metrics['frames'] < 200
                    or abs(metrics['seconds'] - seconds) > 2):
                raise ValueError(f'Incomplete measured window in {jvm}/{workload}')
            if not math.isclose(metrics['average_fps'], metrics['frames'] / metrics['seconds'], rel_tol=1e-9):
                raise ValueError(f'FPS does not match frame count and duration in {jvm}/{workload}')
            key = (result['sparse'], workload, result['label'])
            groups.setdefault(key, []).append(metrics)
    if identities != expected:
        raise ValueError(f'Incomplete or unexpected JVM matrix: missing={expected - identities}, extra={identities - expected}')
    return [{'sparse': sparse, 'workload': workload, 'label': label, 'jvms': len(rows),
             'metrics': {name: {'median': statistics.median(row[name] for row in rows),
                                'min': min(row[name] for row in rows), 'max': max(row[name] for row in rows)}
                         for name in ('average_fps', 'one_percent_low_fps', 'median_ms', 'p95_ms', 'p99_ms', 'max_ms')}}
            for (sparse, workload, label), rows in sorted(groups.items())]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    rows = summarize(json.loads((args.directory / 'results.json').read_text()),
                     json.loads((args.directory / 'metadata.json').read_text()))
    (args.directory / 'summary.json').write_text(json.dumps(rows, indent=2))
    lines = ['# Client FPS results', '', 'Medians of independent JVM summaries. 1% low is the inverse mean of the slowest 1% of frame intervals.', '',
             '| Height | Workload | Variant | JVMs | Average FPS | 1% low FPS | Frame p95 ms | Frame p99 ms | Worst frame ms |',
             '| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    for row in rows:
        values = row['metrics']
        lines.append('| ' + ' | '.join(['Y=4096' if row['sparse'] else 'Y=80', row['workload'], row['label'], str(row['jvms']),
                     *[f"{values[name]['median']:.3f}" for name in ('average_fps', 'one_percent_low_fps', 'p95_ms', 'p99_ms', 'max_ms')]]) + ' |')
    (args.directory / 'summary.md').write_text('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
