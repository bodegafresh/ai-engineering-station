# H02-D2C2f.12x — COMMIT acknowledgement ambiguity

Baseline `2f5af86fca2f8b7372f7567aaf767f3ff482ce37` (PR #139).

Package-private test-only observer executes after real SQLite COMMIT succeeds, then raises SQLException once. Reopened database must contain exactly one admission and ALLOW audit; duplicate admission must be denied.

This is a post-COMMIT caller acknowledgement fault, **not** an exception inside COMMIT. No power-loss durability, cross-store atomicity, complete CDI revocation/tamper coverage or fencing certification. Overall NO-GO.
