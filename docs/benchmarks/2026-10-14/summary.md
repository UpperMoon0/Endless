# Dedicated-server performance results

Each cell is the median of the independent JVM summaries. Tick tails remain separate; CPU and allocation use per-JVM means.

| Height | Workload | Variant | JVMs | Tick median ms | Tick p95 ms | CPU mean ms/tick | Allocation KiB/tick |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| Y=80 | edits | candidate | 5 | 17.6306 | 31.0903 | 11.7031 | 2176.84 |
| Y=80 | edits | current | 5 | 24.4777 | 37.3852 | 19.0156 | 2385.64 |
| Y=80 | edits | fabric | 5 | 15.4989 | 24.7389 | 10.3594 | 1966.02 |
| Y=80 | edits | vanilla | 5 | 15.5882 | 25.9935 | 10.2031 | 1965.60 |
| Y=80 | idle | candidate | 5 | 0.0519 | 0.1142 | 0.0781 | 16.02 |
| Y=80 | idle | current | 5 | 0.0543 | 0.1236 | 0.0625 | 15.96 |
| Y=80 | idle | fabric | 5 | 0.0464 | 0.1237 | 0.0781 | 15.73 |
| Y=80 | idle | vanilla | 5 | 0.0513 | 0.1381 | 0.0781 | 15.53 |
| Y=80 | reads | candidate | 5 | 0.3125 | 0.7959 | 0.3906 | 248.85 |
| Y=80 | reads | current | 5 | 0.3369 | 0.6839 | 0.3750 | 252.62 |
| Y=80 | reads | fabric | 5 | 0.2776 | 0.5672 | 0.2969 | 236.65 |
| Y=80 | reads | vanilla | 5 | 0.2806 | 0.6389 | 0.3125 | 236.24 |
| Y=4096 | edits | candidate | 5 | 6.7636 | 8.1359 | 6.6719 | 4077.47 |
| Y=4096 | edits | current | 5 | 12.1418 | 14.2859 | 11.8125 | 4309.93 |
| Y=4096 | idle | candidate | 5 | 0.0543 | 0.1450 | 0.0781 | 16.10 |
| Y=4096 | idle | current | 5 | 0.0504 | 0.1273 | 0.0625 | 16.01 |
| Y=4096 | reads | candidate | 5 | 0.3080 | 0.6602 | 0.3594 | 241.11 |
| Y=4096 | reads | current | 5 | 0.3397 | 0.7167 | 0.3906 | 245.09 |

## Candidate change against current Endless

Negative percentages mean less time or allocation. These workloads are never combined into an overall score.

| Height | Workload | Tick median | Tick p95 | CPU mean | Allocation mean |
| --- | --- | ---: | ---: | ---: | ---: |
| Y=80 | idle | -4.4% | -7.6% | +25.0% | +0.4% |
| Y=80 | reads | -7.3% | +16.4% | +4.2% | -1.5% |
| Y=80 | edits | -28.0% | -16.8% | -38.5% | -8.8% |
| Y=4096 | idle | +7.7% | +13.9% | +25.0% | +0.5% |
| Y=4096 | reads | -9.3% | -7.9% | -8.0% | -1.6% |
| Y=4096 | edits | -44.3% | -43.0% | -43.5% | -5.4% |
