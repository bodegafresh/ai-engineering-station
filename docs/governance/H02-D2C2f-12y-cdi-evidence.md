# H02-D2C2f.12y — independent negative CDI profile

Baseline `7fe8968c0aecd0d4e8c8371a22947d42c48614fc` (PR #140).

An independent `@QuarkusTest` profile reproduces the established positive fixture with its own temporary workspace, registry, handoff database, policy database and loopback HTTP model stub. Within one test, mutate the approved Java source while keeping the approved SHA, require Event Bus failure with zero HTTP calls; restore source, revoke the exact policy through SQLite, require Event Bus failure with zero HTTP calls and no publication admission.

This covers source tampering and **pre-invocation** revocation. It does not certify tampered journals, files after publication, race fencing or power-loss durability. Overall H02-D2C2f: NO-GO.
