# H02-D2C2f.12u — Independent-JVM unfinished recovery race

Baseline: `77fbb31ff936721222ef763b887f68fba8c1f600` (PR #136).

## Added evidence
Two independent JVMs start behind a shared file gate and contend for an unfinished governed publication after each of three real abrupt-crash boundaries. At least one contender must recover, and the parent must replay a valid artifact without a second HTTP call or hash divergence.

## Important limitations
The test allows the losing contender to fail: no claim of both calls succeeding. It is not a proof of deterministic simultaneous execution at the filesystem operation boundary. It does not inject SQLite COMMIT failures or exercise the negative CDI matrix. This is a bounded diagnostic increment, **not full 12u certification**. Production code remains unchanged. H02-D2C2f stays NO-GO.
