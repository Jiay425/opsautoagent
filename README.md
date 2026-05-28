# CodeOps Incident-to-Fix Agent

An LLM-first Incident-to-Fix agent framework. Alert comes in → LLM agents analyze evidence → Code Repair Agent proposes and applies a source patch → Compile gate validates → Test verification runs → Reflection loop retries failures (max 3 rounds) → Release risk analysis.

The LLM decides localization and fix strategy autonomously. Deterministic guardrails enforce scope, apply patches, validate compilation, and handle rollback.

## Pipeline

```
Alertmanager Webhook
  → OpsDiagnosisSkill (Prometheus + ES logs + SkyWalking trace)
  → RepoUnderstandingSkill (code localization + FixStrategy)
  → EngineeringKnowledgeRagSkill (runbook + architecture docs)
  → BugFixSkill (LLM generates patch)
    → PatchScopeGuardService (deterministic scope enforcement)
    → PatchApplyService (apply to working tree)
    → Compile Gate (mvn compile)
  → TestVerificationSkill (scaffold + run tests)
  → ReleaseRiskSkill (rollback plan, observation metrics)
  → Reflection Loop (max 3 rounds, structured diagnostics fed back to LLM)
```

## Key Features

### Repair Scope System (Phase 1)

| scopeType | Trigger | Behavior |
|-----------|---------|----------|
| `STRICT_SINGLE_METHOD` | 1 method, high confidence evidence | Only listed method modified; others byte-for-byte preserved |
| `MULTI_METHOD` | 2-3 methods with evidence backing | Listed methods + signature adjustments |
| `FULL_FILE` | Broad incident (>3 methods) | Minimal changes preferred |
| `NO_CODE_FIX` | Runtime/config/capacity incident | No patch generated; short-circuits to risk analysis |

### PatchScopeGuard (Phase 2)

A deterministic (non-LLM) guard that validates patches against `repairScope` before applying:

- `NO_CODE_FIX` → rejects any non-empty patch
- `STRICT_SINGLE_METHOD` → rejects modifications to non-target methods
- `MULTI_METHOD` → rejects modifications outside allowed set
- Detects `changedMethods` via lightweight Java method parsing
- In-memory `unifiedDiffPatch` application for reliable detection
- Guard violations feed into reflection loop as `SCOPE_GUARD_FAILED`

### Reflection Retry (Phase 3)

Structured failure diagnostics with 9 failure types:

`SCOPE_GUARD_FAILED` | `PATCH_APPLY_FAILED` | `COMPILE_FAILED` | `SOURCE_STRUCTURE_INVALID` | `TEST_COMPILE_FAILED` | `TEST_ASSERTION_FAILED` | `TEST_PATCH_APPLY_FAILED` | `TEST_TIMEOUT` | `UNKNOWN`

Each diagnostic includes `mustFix`, `mustAvoid`, `nextAttemptConstraints`, and `repairScope`. Max 3 rounds; exhaustion triggers `ReleaseRiskSkill` with manual handoff.

### Eval Harness (Phase 4)

- 7 built-in eval cases across all scope types
- JSON + Markdown report generation (auto-persisted to `data/codeops-eval/`)
- 6 metrics: scope accuracy, patch apply rate, compile pass rate, test pass rate, reflection recovery rate, no-code-fix accuracy
- Report API: `GET /api/v1/codeops/evaluation/report`

## Eval Cases

| Case | scopeType | Description |
|------|-----------|-------------|
| `incident-order-create-npe` | STRICT_SINGLE_METHOD | NPE in OrderSubmitService.submit — null userId passed to repository |
| `incident-inventory-oversell-concurrency` | FULL_FILE | Inventory oversell with concurrent flash sale — race conditions, idempotency gaps |
| `incident-gc-latency-spike` | NO_CODE_FIX | GC pauses causing P99 latency — JVM runtime issue, no code fix |
| `incident-db-pool-runtime-pressure` | NO_CODE_FIX | HikariCP pool exhaustion — config/capacity issue |
| `incident-order-submit-5xx-npe` | CODE_FIX | Generic 5xx with NPE evidence |
| `scope-violation-reflection` | STRICT_SINGLE_METHOD | Tests guard → reflection → recovery when LLM over-scopes |
| `test-assertion-reflection` | FULL_FILE | Tests assertion failure → reflection → recovery for concurrency fixes |

## Architecture

```
ops-autoagent-api/          External API contracts (DTOs)
ops-autoagent-app/          Spring Boot application entry point
ops-autoagent-domain/       Core agent logic
  ├── agent/bugfix/         Code Repair Agent + prompts
  ├── agent/eval/           Evaluation engine + report builder
  ├── agent/orchestrator/   IncidentFixOrchestratorPolicy
  ├── agent/patch/          PatchScopeGuard, PatchApply, PatchValidation
  ├── agent/skill/          7 engineering skills
  ├── agent/llm/            CompatibleChatClient (OpenAI-compatible)
  ├── agent/release/        Release Risk Agent
  ├── agent/test/           Test Verification + scaffolding
  ├── agent/fixture/        Incident fixture evidence loader
  ├── agent/localization/   Code Localization Agent
  └── service/              EngineeringTaskAgentService (task run loop)
ops-autoagent-infrastructure/  External gateways (Prometheus, ES, SkyWalking, RAG)
ops-autoagent-trigger/      HTTP controllers (alert webhook, eval, task)
fixtures/                   7 incident fixture sets (alert, metrics, logs, traces)
samples/order-service/      Target sample project for eval
```

## Requirements

- JDK 17
- Maven 3.8+
- An OpenAI-compatible chat API (tested with DeepSeek v4-flash/v4-pro)

Optional (for live diagnosis mode):
- MySQL 8+
- PostgreSQL with pgvector
- Prometheus, Elasticsearch, SkyWalking

## Build

```powershell
$env:JAVA_HOME = "D:\Java\jdk17"
mvn -q -DskipTests compile install
```

## Run

```powershell
$env:JAVA_HOME = "D:\Java\jdk17"
$env:OPENAI_API_KEY = "your-api-key"
$env:OPENAI_BASE_URL = "https://api.deepseek.com"
$env:OPENAI_CHAT_MODEL = "deepseek-v4-flash"

mvn -pl ops-autoagent-app spring-boot:run -Dspring-boot.run.profiles=full
```

## API Endpoints

### Evaluation

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/codeops/evaluation/run` | Run all 7 built-in cases |
| POST | `/api/v1/codeops/evaluation/run/{caseId}` | Run single case |
| GET | `/api/v1/codeops/evaluation/report` | Get latest structured eval report |

### Tasks

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/codeops/task/{taskId}` | Get task trace with all step outputs |

### Alert Ingestion

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/ops/alert/webhook/alertmanager` | Alertmanager webhook receiver |

## Security

- No API keys, tokens, or passwords are committed to this repository.
- `.claude/` is gitignored (local Claude Code settings).
- Runtime credentials are provided via environment variables.
- The `application-full.yml` uses `${ENV_VAR:}` placeholders with safe defaults.
