# H02-D2C2f.12t — Independent-JVM recovery and concurrent replay

Baseline `main` `2c96374e8e7f2027195c2add53ab12e86b5bb96b`, PR #135.

## Evidence scope

- One JVM abruptly halts at each of three publication boundaries; a **different** JVM recovers from the durable journal, then the parent verifies replay and no second HTTP request.
- Two independently started JVMs concurrently **replay an already completed** governed attempt; both must succeed and preserve one logical artifact ID.
- Tests use real policy, execution handoff, communication SQLite stores and the loopback model fixture.

## Unresolved

The concurrent test is replay-only, **not two simultaneous writers recovering an unfinished attempt**. There is no deterministic SQLite COMMIT failure injection or negative CDI matrix. These remain 12u work. The current implementation does not claim crash consistency under sudden power loss. Do not mark H02-D2C2f closed.
