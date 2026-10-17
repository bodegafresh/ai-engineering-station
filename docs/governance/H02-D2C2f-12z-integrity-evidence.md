# H02-D2C2f.12z — replay integrity negatives

Baseline main `f4238a9db5394cad10f5c514f67d2bac1038547a` (PR #141).

The real CDI Event Bus positive flow is extended, **within the same test**, with two negative replay phases: modify the published file then restore it; modify the durable `execution_attempt_effects.document_sha256` journal value then restore it. Each replay must fail, without another HTTP invocation. The successful admission remains durable.

**Scope:** This proves rejection of simple post-publication file tamper and a mismatched journal digest. It does **not** prove resistance to coordinated replacement of file and journal, DB compromise, malicious administrators, writer fencing, power loss or cross-store atomicity. Overall H02-D2C2f: NO-GO.
