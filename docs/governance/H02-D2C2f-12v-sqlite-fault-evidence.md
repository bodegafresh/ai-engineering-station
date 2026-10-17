# H02-D2C2f.12v — SQLite admission transaction fault evidence

Baseline `2457c327490e2803489646e653741b6197eb3436` (PR #137).

## Faults tested

1. Real SQLite trigger raises `ABORT` during publication admission INSERT after an ALLOW audit is written within `BEGIN IMMEDIATE`. The store must roll back both, even across reopened connections. After dropping the trigger, the same attempt can be admitted normally.
2. Client-observed lost acknowledgement **simulated after a successful COMMIT**: reopen and query admission; blind duplicate admission is rejected and exactly one ALLOW audit remains.

## Limits

This does **not** inject an exception inside the JDBC COMMIT call, emulate a filesystem crash or demonstrate fsync/power-loss durability. No CDI-negative matrix or production fencing changes. Therefore this is a bounded 12v transaction slice, not the entire certification. H02-D2C2f remains NO-GO.
