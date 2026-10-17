# H02-D2C2f.12w — negative CDI matrix

Baseline: `a5383eac13b7c3629447ca8170ab610364e50e1d` (PR #138).

Test class: `GovernedDocumentationQuarkusPositiveTest`, real `@QuarkusTest` with Quarkus-managed `WorkspaceDocumentationEventBus`.

Negative cases: relative traversal, absolute path, wrong approved SHA, forged attempt ID, missing workflow ID. Every case expects Event Bus request failure and unchanged model HTTP counter. The positive/replay test remains.

This is bounded input and authorization coverage, not a complete negative CDI certification. JDBC COMMIT fault seam, revocation during CDI flow, cross-store atomicity and power-loss durability remain outstanding. H02-D2C2f: NO-GO.
