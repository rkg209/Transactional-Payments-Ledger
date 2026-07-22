# SPEC 0008 -- benchmark results

Each row is the median of 3 JMH measurement iterations for that (strategy, isolation, contention) cell; success/retry/failure counts are summed across the 3.

| strategy | isolation | contention | transfers/sec | p50 (ms) | p99 (ms) | p999 (ms) | successes | retry exhausted | other failures |
|---|---|---|---|---|---|---|---|---|---|
| optimistic | read_committed | 1 | 336.7 | 3 | 6 | 6 | 90 | 0 | 0 |
| optimistic | read_committed | 2 | 252.0 | 2 | 174 | 174 | 180 | 0 | 0 |
| optimistic | read_committed | 4 | 269.3 | 2 | 195 | 404 | 360 | 0 | 0 |
| optimistic | read_committed | 8 | 141.2 | 2 | 394 | 817 | 711 | 9 | 0 |
| optimistic | read_committed | 16 | 161.8 | 1 | 390 | 812 | 1377 | 63 | 0 |
| optimistic | read_committed | 32 | 180.2 | 1 | 384 | 887 | 2667 | 213 | 0 |
| optimistic | read_committed | 64 | 233.3 | 1 | 762 | 845 | 4924 | 836 | 0 |
| optimistic | serializable | 1 | 248.0 | 3 | 20 | 20 | 90 | 0 | 0 |
| optimistic | serializable | 2 | 569.3 | 2 | 61 | 61 | 180 | 0 | 0 |
| optimistic | serializable | 4 | 132.0 | 2 | 375 | 798 | 360 | 0 | 0 |
| optimistic | serializable | 8 | 188.4 | 2 | 393 | 808 | 711 | 9 | 0 |
| optimistic | serializable | 16 | 158.6 | 2 | 400 | 823 | 1377 | 63 | 0 |
| optimistic | serializable | 32 | 134.0 | 4 | 789 | 1028 | 2524 | 356 | 0 |
| optimistic | serializable | 64 | 102.3 | 4 | 807 | 1240 | 4082 | 1678 | 0 |
| pessimistic | read_committed | 1 | 370.6 | 2 | 6 | 6 | 90 | 0 | 0 |
| pessimistic | read_committed | 2 | 522.0 | 3 | 15 | 15 | 180 | 0 | 0 |
| pessimistic | read_committed | 4 | 842.7 | 5 | 9 | 10 | 360 | 0 | 0 |
| pessimistic | read_committed | 8 | 704.3 | 11 | 17 | 19 | 720 | 0 | 0 |
| pessimistic | read_committed | 16 | 858.7 | 17 | 35 | 43 | 1440 | 0 | 0 |
| pessimistic | read_committed | 32 | 733.6 | 20 | 593 | 872 | 2880 | 0 | 0 |
| pessimistic | read_committed | 64 | 643.0 | 60 | 441 | 734 | 5760 | 0 | 0 |
| pessimistic | serializable | 1 | 251.5 | 3 | 25 | 25 | 90 | 0 | 0 |
| pessimistic | serializable | 2 | 540.3 | 2 | 66 | 66 | 180 | 0 | 0 |
| pessimistic | serializable | 4 | 138.3 | 2 | 375 | 793 | 360 | 0 | 0 |
| pessimistic | serializable | 8 | 191.3 | 2 | 383 | 803 | 711 | 9 | 0 |
| pessimistic | serializable | 16 | 137.5 | 2 | 405 | 823 | 1367 | 73 | 0 |
| pessimistic | serializable | 32 | 139.2 | 1 | 406 | 829 | 2560 | 320 | 0 |
| pessimistic | serializable | 64 | 130.9 | 2 | 801 | 923 | 4318 | 1442 | 0 |

**Crossover (read_committed):** pessimistic overtakes optimistic at 1 threads/hot account and stays ahead through the swept range

**Crossover (serializable):** pessimistic overtakes optimistic at 32 threads/hot account and stays ahead through the swept range

**SERIALIZABLE cost (optimistic):** 0.3% average throughput reduction vs READ COMMITTED

**SERIALIZABLE cost (pessimistic):** 61.4% average throughput reduction vs READ COMMITTED

## Claim

Sustained 569 transfers/sec at p99 61 ms under serializable isolation (optimistic strategy, contention 2 threads/hot account); optimistic wins below contention level 1, pessimistic at or above it (read_committed).

## Issues / surprises

See progress_report.md for this spec's entry.
