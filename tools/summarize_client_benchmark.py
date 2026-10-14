#!/usr/bin/env python3
"""Keep individual client workloads and tails visible; aggregate independent JVMs."""
import argparse
import json
from pathlib import Path
import statistics


def summarize(results):
    groups = {}
    identities = set()
    for result in results:
        identity = (result['label'], result['sparse'], result['repeat'])
        if identity in identities:
            raise ValueError('Duplicate JVM receipt')
        identities.add(identity)
        for workload, metrics in result['metrics'].items():
            key = (result['sparse'], workload, result['label'])
            groups.setdefault(key, []).append(metrics)
    return [{'sparse': sparse, 'workload': workload, 'label': label, 'jvms': len(rows),
             'metrics': {name: {'median': statistics.median(row[name] for row in rows),
                                'min': min(row[name] for row in rows), 'max': max(row[name] for row in rows)}
                         for name in ('average_fps', 'one_percent_low_fps', 'median_ms', 'p95_ms', 'p99_ms', 'max_ms')}}
            for (sparse, workload, label), rows in sorted(groups.items())]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    rows = summarize(json.loads((args.directory / 'results.json').read_text()))
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
