# H02-D2C2f.12r — bounded material-integrity negative tests

Baseline: `main` `5bf175eb3d03071a3eae68bbce512a5f2b6262f1`, PR #133.

This slice tests altered staging after `FILE_WRITTEN`, altered final material after `ATOMIC_MOVE`, and altered material after completed `ARTIFACT_RECORDED`. Each test asserts a `SecurityException` and no second HTTP request. The existing real governed fixture, policy SQLite, journal and loopback model are reused.

**Not delivered:** separate-JVM concurrent recovery, negative CDI matrix, SQLite COMMIT failure injection, process-level fencing or power-loss durability. These remain blockers to governance closure. The scope is intentionally narrower than the full 12r plan rather than claiming untested safety.

Next 12s: independent-JVM concurrent recovery harness and deterministic SQLite COMMIT fault injection; update assurance matrix and formal GO/NO-GO.
