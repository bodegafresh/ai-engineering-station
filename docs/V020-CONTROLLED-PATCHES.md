# AIES v0.20 — Human-approved single-file patch requests

## Scope
- Opt-in API at `/api/v1/patches`, independent of team-model execution.
- Dedicated 32+ character `AIES_PATCHES_TOKEN`; bind HTTP to loopback only.
- `prepare` stages a **complete replacement** of an existing, allowlisted text file. Does not write.
- `approve` requires explicit original and proposed SHA-256 values; `apply` requires approved proposal.
- Original SHA-256 rechecked before replacement; expired proposals (15 minutes) cannot be used.
- In-memory staging, 100 concurrent proposals maximum, 32 KiB maximum per file; no arbitrary shell execution.
- One file at a time. No automatic generation of patches from LLM responses; a human/client supplies replacement content.

## API example
Start Quarkus after setting `AIES_PATCHES_ENABLED=true`, `AIES_PATCHES_TOKEN=$(openssl rand -hex 32)`, and `AIES_TEAMS_PROJECTS_ROOT="$PWD/projects"` in the **same terminal**. Keep the token private. Ensure `projects/hello_aies/App.java` exists.

```bash
curl -sS -X POST http://127.0.0.1:8080/api/v1/patches/prepare \
 -H "X-AIES-Patch-Token: $AIES_PATCHES_TOKEN" -H 'Content-Type: application/json' \
 --data '{"projectId":"hello_aies","path":"App.java","replacement":"public class App { public String hello() { return \"Hello v20\"; } }\n"}' \
 | tee /tmp/aies-v020-proposal.json | python3 -m json.tool
```

Inspect the proposal and independently inspect the proposed code. For the demo, use Python to build the approval body from the returned hashes; **do not automate this step in production**:

```bash
ID=$(python3 -c 'import json; print(json.load(open("/tmp/aies-v020-proposal.json"))["id"])')
python3 - <<'PY' > /tmp/aies-v020-approval.json
import json
p=json.load(open('/tmp/aies-v020-proposal.json'))
print(json.dumps({'originalSha256':p['originalSha256'],'proposedSha256':p['proposedSha256']}))
PY
curl -sS -X POST "http://127.0.0.1:8080/api/v1/patches/$ID/approve" \
 -H "X-AIES-Patch-Token: $AIES_PATCHES_TOKEN" -H 'Content-Type: application/json' \
 --data-binary @/tmp/aies-v020-approval.json
curl -sS -X POST "http://127.0.0.1:8080/api/v1/patches/$ID/apply" \
 -H "X-AIES-Patch-Token: $AIES_PATCHES_TOKEN"
```

## Important security and engineering limitations
- Not a sandbox: workspace must be a **trusted local sample project**, not an adversarial directory.
- Path and symlink checks are not race-free against concurrent filesystem mutations. A hostile local process could exploit TOCTOU. Run under a low-privilege account with a dedicated sandbox before untrusted repositories.
- Atomic replacement does not preserve the original file's metadata/permissions and is not a transaction across files.
- No git worktree, rollback, persistent approvals, audit database, dry-run diff, or automatic tests yet.
- Model-generated code is untrusted. This endpoint is not wired to model outputs by design.
- Do not enable this endpoint for remote access or production use.
