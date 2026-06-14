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

### Cross-File Incident-to-Fix Verification

The latest hardening focuses on incidents where the top stack frame is not the whole root cause. In the cross-file idempotency case, the alert points to `OrderSubmitService.submitFlashSale`, while the actual race sits in the idempotency component. The framework now treats the first localization result as a candidate boundary, then lets the repair agent expand the scope only inside verified candidate files.

Latest verified LLM run:

| Item | Value |
|------|-------|
| Case | `scope-expansion-cross-file-idempotency` |
| Symptom | Duplicate `requestId` accepted twice; 5xx and order conflicts increase |
| Result | `SUCCESS` |
| Pipeline | Ops diagnosis → code localization → RAG → patch generation → Guard failure → reflection retry → test failure → failure log feedback → patch retry → tests pass → release risk |
| Coverage | code localization `1.0`, patch `1.0`, test `1.0`, risk `1.0` |
| Safety | Patch applied in isolated sandbox; compile and Maven test verification passed |
| Artifacts | JSON report, Markdown report, full trace JSON, patch diff |

The repair agent receives a `CodeContextPack`, not only search hits. It includes primary suspect files, candidate expansion files, same-package dependencies, related tests, build files, and a short reason for each snippet. This keeps the flow LLM-first while giving deterministic guardrails enough structure to block hallucinated or out-of-scope patches.

### Evidence-Driven Code Localization

Code localization now builds an `EvidenceGraph` before asking the LLM to choose files and methods. The graph connects incident signals to repository evidence:

```
alert/log/trace/metric signal
  → repository search match
  → code snippet
  → method/file node
  → same-package call relation
```

This matters for cross-file incidents. For example, an alert may only mention `OrderSubmitService.submitFlashSale`; `IdempotencyService` becomes a candidate only after the visible code shows calls such as `idempotencyService.alreadyProcessed()` and `idempotencyService.markProcessed()`. The prompt requires the localization agent to cite this evidence chain rather than treating search hits as conclusions.

Trace surfaces now expose:

| Field | Purpose |
|-------|---------|
| `evidenceGraphSummary` | Compact graph size and top code nodes |
| `evidenceGraphRankedCodeNodes` | Ranked code candidates with scores |
| `evidenceGraph` | Full compact node/edge graph for debugging and reports |

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

**Agent Loop Harness (Phase 5)** — Claude Code-style model-driven loop for CodeOps debugging. The model sees a typed tool catalog, emits JSON tool calls, `ToolPermissionGate` enforces policy, `EngineeringToolRegistry` dispatches allowed tools, and compact trace items are returned by default. A `dryRun` mock model verifies the loop locally without sending repository data to an external LLM.

| Component | Responsibility |
|-----------|----------------|
| `AgentLoopService` | Turn loop: model decision → permission gate → tool execution → next turn/final answer |
| `CodeOpsAgentLoopModelClient` | OpenAI-compatible LLM adapter; parses JSON decisions and tolerates common field aliases |
| `EngineeringToolRegistry` | Central tool catalog, argument schema, handler dispatch |
| `ToolPermissionGate` | Unknown/disabled/tool-scope/command/write/high-risk approval checks |
| `MockCodeOpsAgentLoopModelClient` | Local dry-run client for no-network loop verification |

Registered tools currently include `repo.create_snapshot`, `repo.search_text`, `repo.read_file_snippet`, `repo.git_diff`, and `repo.maven`. The snippet tool accepts both `centerLine/radius` and `startLine/endLine` argument styles.

### Eval Cases

| Case | scopeType | Description |
|------|-----------|-------------|
| `incident-order-create-npe` | STRICT_SINGLE_METHOD | NPE in submit() — null userId |
| `incident-inventory-oversell-concurrency` | FULL_FILE | Inventory oversell + race conditions |
| `incident-gc-latency-spike` | NO_CODE_FIX | GC pauses — JVM runtime issue |
| `incident-db-pool-runtime-pressure` | NO_CODE_FIX | HikariCP pool exhaustion |
| `scope-violation-reflection` | STRICT_SINGLE_METHOD | Guard catches over-scope → reflection recovers |
| `test-assertion-reflection` | FULL_FILE | Test assertion failure → reflection recovers |
| `scope-expansion-cross-file-idempotency` | FULL_FILE | Stack top in order submit method, root cause expands to idempotency component |

### Architecture

```
ops-autoagent-api/          API contracts (DTOs)
ops-autoagent-app/          Spring Boot entry point
ops-autoagent-domain/       Core agent logic
  ├── agent/bugfix/         Code Repair Agent
  ├── agent/eval/           Evaluation engine + report builder
  ├── agent/loop/           Model-driven agent loop + compact trace
  ├── agent/tool/           Tool registry, permission gate, tool runtime audit
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
mvn -pl ops-autoagent-app spring-boot:run `
  -Dspring-boot.run.profiles=full `
  -Dspring-boot.run.arguments="--spring.ai.openai.chat.options.model=deepseek-v4-flash --codeops.agent.llm.compatible-client.path=/chat/completions"
```

### API

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/codeops/evaluation/run` | Run all 7 cases |
| POST | `/api/v1/codeops/evaluation/run/{caseId}` | Run single case |
| GET | `/api/v1/codeops/evaluation/report` | Latest eval report |
| GET | `/api/v1/codeops/task/{taskId}` | Task trace |
| POST | `/api/v1/codeops/agent-loop/run` | Run model-driven tool loop; supports `dryRun` and `includeSteps` |

Agent loop request example:

```json
{
  "goal": "Search OrderService related code and summarize likely tests.",
  "repository": "E:/DeskTop/java_project/ops-autoagent-diagnosis/samples/order-service",
  "maxTurns": 5,
  "dryRun": false,
  "includeSteps": false
}
```

The default response includes a compact `trace`. Set `includeSteps=true` only when full permission policy and tool output details are needed for debugging.

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

### 跨文件 Incident-to-Fix 验证

最新一轮硬化重点解决“栈顶方法不等于完整根因”的问题。在跨文件幂等 case 中，告警和 Trace 首先指向 `OrderSubmitService.submitFlashSale`，但真正竞态点在幂等组件。现在系统不会把第一层定位结果锁死为最终修复边界，而是把它当成候选边界；修复 Agent 可以基于完整代码上下文扩展范围，但只能在已验证的候选文件内扩展。

最新真实 LLM 验证结果：

| 项目 | 结果 |
|------|------|
| Case | `scope-expansion-cross-file-idempotency` |
| 现象 | 重复 `requestId` 被成功处理两次，5xx 和订单冲突升高 |
| 结果 | `SUCCESS` |
| 链路 | 运维诊断 → 代码定位 → RAG → 生成补丁 → Guard 拦截 → 反射重试 → 测试失败 → 失败日志回灌 → 再次修复 → 测试通过 → 发布风险分析 |
| 覆盖率 | 代码定位 `1.0`，补丁 `1.0`，测试 `1.0`，风险 `1.0` |
| 安全性 | 补丁在隔离沙箱应用；编译和 Maven 测试验证通过 |
| 产物 | JSON 报告、Markdown 报告、完整 trace JSON、patch diff |

修复 Agent 接收的是 `CodeContextPack`，不是单纯的搜索命中行。上下文包包含主嫌疑文件、候选扩展文件、同包依赖、相关测试、构建文件，以及每段代码进入上下文的原因。这样既保留 LLM 自主判断，又让确定性 Guard 有足够结构拦截幻觉补丁和越界修改。

### 证据驱动代码定位

代码定位 Agent 调用前会先构建 `EvidenceGraph`。这张图把事故信号和仓库证据串起来：

```
告警/日志/Trace/指标信号
  → 仓库搜索命中
  → 代码片段
  → 方法/文件节点
  → 同包调用关系
```

这对跨文件事故很关键。比如告警可能只提到 `OrderSubmitService.submitFlashSale`；`IdempotencyService` 只有在可见代码里出现 `idempotencyService.alreadyProcessed()` 和 `idempotencyService.markProcessed()` 这类调用关系后，才会进入候选范围。Prompt 要求定位 Agent 引用这条证据链，而不是把搜索结果直接当结论。

Trace 中会直接暴露：

| 字段 | 作用 |
|------|------|
| `evidenceGraphSummary` | 图规模和最高分代码节点摘要 |
| `evidenceGraphRankedCodeNodes` | 带分数的代码候选排序 |
| `evidenceGraph` | 完整轻量节点/边图，用于调试和报告展示 |

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

**Agent Loop Harness（Phase 5）** — 类 Claude Code 的模型驱动循环。模型看到带参数 schema 的工具目录，输出 JSON tool calls；`ToolPermissionGate` 做权限前置校验；`EngineeringToolRegistry` 统一分发工具；接口默认返回轻量 trace。`dryRun` 使用本地 mock 模型验证闭环，不会把仓库内容发送到外部 LLM。

| 组件 | 职责 |
|------|------|
| `AgentLoopService` | 回合循环：模型决策 → 权限 gate → 工具执行 → 下一轮/最终答案 |
| `CodeOpsAgentLoopModelClient` | OpenAI-compatible LLM 适配器；解析 JSON 决策并兼容常见字段别名 |
| `EngineeringToolRegistry` | 统一工具目录、参数 schema、handler 分发 |
| `ToolPermissionGate` | 未知工具、禁用工具、技能约束、命令、写入、高风险审批检查 |
| `MockCodeOpsAgentLoopModelClient` | 本地 dry-run 客户端，用于无网络闭环验证 |

当前已注册工具包括 `repo.create_snapshot`、`repo.search_text`、`repo.read_file_snippet`、`repo.git_diff`、`repo.maven`。代码片段工具同时兼容 `centerLine/radius` 和 `startLine/endLine` 两种参数风格。

### 评测 Case

| Case | scopeType | 描述 |
|------|-----------|------|
| `incident-order-create-npe` | STRICT_SINGLE_METHOD | submit() 空指针 — null userId |
| `incident-inventory-oversell-concurrency` | FULL_FILE | 秒杀库存超卖 + 竞态条件 |
| `incident-gc-latency-spike` | NO_CODE_FIX | GC 暂停 — JVM 运行时问题 |
| `incident-db-pool-runtime-pressure` | NO_CODE_FIX | HikariCP 连接池耗尽 |
| `scope-violation-reflection` | STRICT_SINGLE_METHOD | Guard 拦截越界 → 反射恢复 |
| `test-assertion-reflection` | FULL_FILE | 测试断言失败 → 反射恢复 |
| `scope-expansion-cross-file-idempotency` | FULL_FILE | 栈顶在下单方法，根因扩展到幂等组件 |

### 架构

```
ops-autoagent-api/          API 协议层（DTO）
ops-autoagent-app/          Spring Boot 启动入口
ops-autoagent-domain/       核心 Agent 逻辑
  ├── agent/bugfix/         代码修复 Agent
  ├── agent/eval/           评测引擎 + 报告生成器
  ├── agent/loop/           模型驱动 agent loop + 轻量 trace
  ├── agent/tool/           工具注册表、权限 gate、工具运行审计
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
mvn -pl ops-autoagent-app spring-boot:run `
  -Dspring-boot.run.profiles=full `
  -Dspring-boot.run.arguments="--spring.ai.openai.chat.options.model=deepseek-v4-flash --codeops.agent.llm.compatible-client.path=/chat/completions"
```

### API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/codeops/evaluation/run` | 运行全部 7 个 case |
| POST | `/api/v1/codeops/evaluation/run/{caseId}` | 运行单个 case |
| GET | `/api/v1/codeops/evaluation/report` | 获取最新评测报告 |
| GET | `/api/v1/codeops/task/{taskId}` | 获取任务 trace |
| POST | `/api/v1/codeops/agent-loop/run` | 运行模型驱动工具循环；支持 `dryRun` 和 `includeSteps` |

Agent loop 请求示例：

```json
{
  "goal": "搜索 OrderService 相关代码，并总结可能的测试文件。",
  "repository": "E:/DeskTop/java_project/ops-autoagent-diagnosis/samples/order-service",
  "maxTurns": 5,
  "dryRun": false,
  "includeSteps": false
}
```

默认响应只返回轻量 `trace`。只有调试完整权限策略和工具输出时，才建议设置 `includeSteps=true`。

---

**Security** · No API keys committed. `.claude/` gitignored. Credentials via env vars. `${ENV_VAR:}` placeholders with safe defaults.
