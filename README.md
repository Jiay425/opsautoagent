# CodeOps Incident-to-Fix Agent · 事件到修复智能体

[English](#english) | [中文](#中文)

---

## English

An LLM-first Incident-to-Fix agent framework. Alert comes in → LLM agents analyze evidence → Code Repair Agent proposes and applies a source patch → Compile gate validates → Test verification runs → Reflection loop retries failures (max 3 rounds) → Release risk analysis.

The LLM decides localization and fix strategy autonomously. Deterministic guardrails enforce scope, apply patches, validate compilation, and handle rollback.

### Pipeline

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

### Key Features

### Real Alertmanager Verification

The framework has been verified with real Prometheus + Alertmanager firing alerts, not only fixture cases.

| Scenario | Result | Evidence |
|----------|--------|----------|
| `OrderServiceHttp5xxHigh` | Code fix path completed | Real evidence coverage `1.0`; NPE stack localized to `OrderRepository.create(OrderRepository.java:14)`; patch applied in isolated sandbox; compile gate passed; Maven targeted tests and full `mvn test` passed |
| `OrderServiceLatencyHigh` | No-code path completed | Real evidence coverage `1.0`; endpoint-scoped evidence isolation kept stale `/api/orders/submit` NPE logs out of code localization; no patch generated |

The latest verification runs are written locally under `data/real-chain-runs/` and intentionally ignored by git.

**Repair Scope System (Phase 1)**

| scopeType | Trigger | Behavior |
|-----------|---------|----------|
| `STRICT_SINGLE_METHOD` | 1 method, high confidence | Only listed method modified; others byte-for-byte preserved |
| `MULTI_METHOD` | 2-3 methods with evidence | Listed methods + signature adjustments |
| `FULL_FILE` | Broad incident | Minimal changes preferred |
| `NO_CODE_FIX` | Runtime/config/capacity | No patch; short-circuits to risk analysis |

**PatchScopeGuard (Phase 2)** — Deterministic (non-LLM) patch boundary enforcement. Validates `changedMethods` against `repairScope` before apply. Handles `unifiedDiffPatch` via in-memory application. Violations feed into reflection loop.

**Reflection Retry (Phase 3)** — 9 failure types with structured diagnostics (`mustFix`, `mustAvoid`, `nextAttemptConstraints`). Max 3 rounds; exhaustion triggers `ReleaseRiskSkill`.

**Eval Harness (Phase 4)** — 7 built-in cases, JSON + Markdown auto-reports, 6 metrics, report API.

### Eval Cases

| Case | scopeType | Description |
|------|-----------|-------------|
| `incident-order-create-npe` | STRICT_SINGLE_METHOD | NPE in submit() — null userId |
| `incident-inventory-oversell-concurrency` | FULL_FILE | Inventory oversell + race conditions |
| `incident-gc-latency-spike` | NO_CODE_FIX | GC pauses — JVM runtime issue |
| `incident-db-pool-runtime-pressure` | NO_CODE_FIX | HikariCP pool exhaustion |
| `scope-violation-reflection` | STRICT_SINGLE_METHOD | Guard catches over-scope → reflection recovers |
| `test-assertion-reflection` | FULL_FILE | Test assertion failure → reflection recovers |

### Architecture

```
ops-autoagent-api/          API contracts (DTOs)
ops-autoagent-app/          Spring Boot entry point
ops-autoagent-domain/       Core agent logic
  ├── agent/bugfix/         Code Repair Agent
  ├── agent/eval/           Evaluation engine + report builder
  ├── agent/orchestrator/   IncidentFixOrchestratorPolicy
  ├── agent/patch/          PatchScopeGuard, PatchApply, PatchValidation
  ├── agent/skill/          7 engineering skills
  ├── agent/llm/            CompatibleChatClient (OpenAI-compatible)
  ├── agent/release/        Release Risk Agent
  ├── agent/test/           Test Verification + scaffolding
  └── service/              EngineeringTaskAgentService
ops-autoagent-trigger/      HTTP controllers
fixtures/                   7 incident fixture sets
samples/order-service/      Target sample project
```

### Build & Run

```powershell
$env:JAVA_HOME = "D:\Java\jdk17"
mvn -q -DskipTests compile install

$env:OPENAI_API_KEY = "your-api-key"
$env:OPENAI_BASE_URL = "https://api.deepseek.com"
$env:OPENAI_CHAT_MODEL = "deepseek-v4-flash"
mvn -pl ops-autoagent-app spring-boot:run -Dspring-boot.run.profiles=full
```

### API

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/codeops/evaluation/run` | Run all 7 cases |
| POST | `/api/v1/codeops/evaluation/run/{caseId}` | Run single case |
| GET | `/api/v1/codeops/evaluation/report` | Latest eval report |
| GET | `/api/v1/codeops/task/{taskId}` | Task trace |

---

## 中文

一个 LLM 优先的 Incident-to-Fix 智能体框架。告警接入 → LLM 分析证据 → 代码修复 Agent 生成并应用源码补丁 → 编译门校验 → 测试验证 → 失败回灌反射循环（最多 3 轮）→ 发布风险分析。

LLM 自主决定定位和修复策略。确定性工程守卫负责作用域控制、补丁应用、编译校验和回滚。

### 流水线

```
Alertmanager Webhook
  → OpsDiagnosisSkill（Prometheus + ES 日志 + SkyWalking 链路）
  → RepoUnderstandingSkill（代码定位 + 修复策略判断）
  → EngineeringKnowledgeRagSkill（Runbook + 架构文档）
  → BugFixSkill（LLM 生成补丁）
    → PatchScopeGuardService（确定性作用域校验）
    → PatchApplyService（应用到工作区）
    → Compile Gate（mvn compile）
  → TestVerificationSkill（测试脚手架 + 运行测试）
  → ReleaseRiskSkill（回滚方案 + 观察指标）
  → Reflection Loop（最多 3 轮，结构化诊断回灌给 LLM）
```

### 核心功能

### 真实 Alertmanager 链路验证

项目已经跑过真实 Prometheus + Alertmanager firing 告警链路，不只是 fixture 评测。

| 场景 | 结果 | 验证点 |
|------|------|--------|
| `OrderServiceHttp5xxHigh` | 代码修复链路完成 | 真实证据覆盖率 `1.0`；NPE 栈定位到 `OrderRepository.create(OrderRepository.java:14)`；补丁应用到隔离沙箱；编译门通过；定向 Maven 测试和全量 `mvn test` 通过 |
| `OrderServiceLatencyHigh` | 非代码修复链路完成 | 真实证据覆盖率 `1.0`；按当前告警 endpoint 做证据隔离，旧 `/api/orders/submit` NPE 日志不会污染代码定位；不生成补丁 |

真实链路运行产物保存在本地 `data/real-chain-runs/`，已加入 gitignore，避免提交日志、沙箱和本地运行数据。

**Repair Scope 修复作用域（Phase 1）**

| scopeType | 触发条件 | 行为 |
|-----------|---------|------|
| `STRICT_SINGLE_METHOD` | 1 个方法，高置信度 | 只修改目标方法；其他方法逐字节保留 |
| `MULTI_METHOD` | 2-3 个方法有证据支撑 | 修改列出方法 + 必要签名调整 |
| `FULL_FILE` | 广泛事故 | 最小化改动优先 |
| `NO_CODE_FIX` | 运行时/配置/容量问题 | 不生成补丁；直接跳到风险分析 |

**PatchScopeGuard 补丁守卫（Phase 2）** — 确定性（非 LLM）校验层。在应用前验证补丁是否越界。支持 unifiedDiffPatch 内存 apply。守卫失败作为 `SCOPE_GUARD_FAILED` 进入反射循环。

**Reflection Retry 反射重试（Phase 3）** — 9 种失败类型，结构化诊断。最多 3 轮；耗尽后触发 `ReleaseRiskSkill` 并输出人工接管方案。

**Eval Harness 评测平台（Phase 4）** — 7 个内置 case，JSON + Markdown 报告自动生成，6 项指标，报告 API。

### 评测 Case

| Case | scopeType | 描述 |
|------|-----------|------|
| `incident-order-create-npe` | STRICT_SINGLE_METHOD | submit() 空指针 — null userId |
| `incident-inventory-oversell-concurrency` | FULL_FILE | 秒杀库存超卖 + 竞态条件 |
| `incident-gc-latency-spike` | NO_CODE_FIX | GC 暂停 — JVM 运行时问题 |
| `incident-db-pool-runtime-pressure` | NO_CODE_FIX | HikariCP 连接池耗尽 |
| `scope-violation-reflection` | STRICT_SINGLE_METHOD | Guard 拦截越界 → 反射恢复 |
| `test-assertion-reflection` | FULL_FILE | 测试断言失败 → 反射恢复 |

### 架构

```
ops-autoagent-api/          API 协议层（DTO）
ops-autoagent-app/          Spring Boot 启动入口
ops-autoagent-domain/       核心 Agent 逻辑
  ├── agent/bugfix/         代码修复 Agent
  ├── agent/eval/           评测引擎 + 报告生成器
  ├── agent/orchestrator/   事故修复编排策略
  ├── agent/patch/          PatchScopeGuard + 补丁应用 + 校验
  ├── agent/skill/          7 个工程技能
  ├── agent/llm/            兼容 OpenAI 协议客户端
  ├── agent/release/        发布风险 Agent
  ├── agent/test/           测试验证 + 回归脚手架
  └── service/              EngineeringTaskAgentService
ops-autoagent-trigger/      HTTP 控制器
fixtures/                   7 组事故 fixture
samples/order-service/      评测目标示例项目
```

### 构建与运行

```powershell
$env:JAVA_HOME = "D:\Java\jdk17"
mvn -q -DskipTests compile install

$env:OPENAI_API_KEY = "your-api-key"
$env:OPENAI_BASE_URL = "https://api.deepseek.com"
$env:OPENAI_CHAT_MODEL = "deepseek-v4-flash"
mvn -pl ops-autoagent-app spring-boot:run -Dspring-boot.run.profiles=full
```

### API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/codeops/evaluation/run` | 运行全部 7 个 case |
| POST | `/api/v1/codeops/evaluation/run/{caseId}` | 运行单个 case |
| GET | `/api/v1/codeops/evaluation/report` | 获取最新评测报告 |
| GET | `/api/v1/codeops/task/{taskId}` | 获取任务 trace |

---

**Security** · No API keys committed. `.claude/` gitignored. Credentials via env vars. `${ENV_VAR:}` placeholders with safe defaults.
