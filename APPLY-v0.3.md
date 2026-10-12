# AI Engineering Station — v0.3 local inference patch

**Apply only to the working v0.2 project.** Back up your SQLite `data/` directory and commit v0.2 first.

```bash
cd ~/git/ai-engineering-station
git switch -c feat/local-model-v03
unzip -o ~/Descargas/ai-engineering-station-v0.3-patch.zip -d .
./gradlew clean test
```

Start a local OpenAI-compatible server on `127.0.0.1:8081`. Example using llama.cpp (install/build separately):

```bash
llama-server -m /absolute/path/to/your-model.gguf --host 127.0.0.1 --port 8081 -c 8192 -ngl 99
```

Choose a GGUF model that fits your GPU and verify Vulkan offload in the server logs. `-ngl 99` requests offload; it does not guarantee all layers will fit.

Confirm endpoint:

```bash
curl -s http://127.0.0.1:8081/v1/models | jq
```

Run the orchestrator in another terminal:

```bash
AIES_MODEL_ENABLED=true AIES_MODEL_NAME=local ./gradlew :apps:control-plane:quarkusDev
```

Create task:

```bash
curl -sS -X POST http://127.0.0.1:8080/api/v1/tasks -H 'Content-Type: application/json' -d '{"objective":"Escribe una función Java 21 para validar un RUT chileno, explica el algoritmo y propone 5 pruebas unitarias."}' | jq
```

Copy the `id` from response, then:

```bash
curl -sS http://127.0.0.1:8080/api/v1/tasks/UUID | jq
curl -sS http://127.0.0.1:8080/api/v1/tasks/UUID/result | jq
```

Expect PENDING -> RUNNING -> COMPLETED, and a stored model response. A disconnected or failed model produces FAILED. If disabled, tasks stay PENDING and will be processed when enabled.

**Security:** No model output is executed. No external providers. HTTP model URL is loopback-only. Keep private repository data and secrets out of prompts.
