# CodeOps Incident-to-Fix Agent（事件到修复智能体）

一个 LLM 优先的 Incident-to-Fix 智能体框架。告警接入 → LLM 分析证据 → 代码修复 Agent 生成并应用源码补丁 → 编译门校验 → 测试验证 → 失败回灌反射循环（最多 3 轮）→ 发布风险分析。

LLM 自主决定定位和修复策略。确定性工程守卫负责作用域控制、补丁应用、编译校验和回滚。

## 流水线

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

## 核心功能

### Repair Scope 修复作用域系统（Phase 1）

| scopeType | 触发条件 | 行为 |
|-----------|---------|------|
| `STRICT_SINGLE_METHOD` | 1 个方法，高置信度证据 | 只修改目标方法；其他方法逐字节原样保留 |
| `MULTI_METHOD` | 2-3 个方法有证据支撑 | 修改列出方法 + 必要签名调整 |
| `FULL_FILE` | 广泛事故（>3 个方法） | 最小化改动优先 |
| `NO_CODE_FIX` | 运行时/配置/容量问题 | 不生成补丁；直接跳到风险分析 |

### PatchScopeGuard 补丁作用域守卫（Phase 2）

确定性（非 LLM）校验层，在应用补丁前验证补丁是否符合 `repairScope`：

- `NO_CODE_FIX` → 拒绝任何非空补丁
- `STRICT_SINGLE_METHOD` → 拒绝修改非目标方法
- `MULTI_METHOD` → 拒绝修改允许列表之外的方法
- 通过轻量 Java 方法解析检测 `changedMethods`
- 支持 unifiedDiffPatch 的内存 apply 以确保可靠检测
- 守卫失败作为 `SCOPE_GUARD_FAILED` 进入反射循环

### Reflection Retry 反射重试（Phase 3）

结构化失败诊断，覆盖 9 种失败类型：

`SCOPE_GUARD_FAILED` | `PATCH_APPLY_FAILED` | `COMPILE_FAILED` | `SOURCE_STRUCTURE_INVALID` | `TEST_COMPILE_FAILED` | `TEST_ASSERTION_FAILED` | `TEST_PATCH_APPLY_FAILED` | `TEST_TIMEOUT` | `UNKNOWN`

每个诊断包含 `mustFix`（必须修什么）、`mustAvoid`（不能犯什么）、`nextAttemptConstraints`（下轮硬约束）和 `repairScope`。最多 3 轮；耗尽后触发 `ReleaseRiskSkill` 并输出人工接管方案。

### Eval Harness 评测平台（Phase 4）

- 7 个内置评测 case，覆盖所有作用域类型
- JSON + Markdown 报告自动生成（持久化到 `data/codeops-eval/`）
- 6 项指标：作用域准确率、补丁应用率、编译通过率、测试通过率、反射恢复率、非代码修复准确率
- 报告 API：`GET /api/v1/codeops/evaluation/report`

## 评测 Case

| Case | scopeType | 描述 |
|------|-----------|------|
| `incident-order-create-npe` | STRICT_SINGLE_METHOD | OrderSubmitService.submit 空指针 — null userId 传入 repository |
| `incident-inventory-oversell-concurrency` | FULL_FILE | 秒杀库存超卖 — 竞态条件、幂等缺口 |
| `incident-gc-latency-spike` | NO_CODE_FIX | GC 暂停导致 P99 延迟飙高 — JVM 运行时问题，不需要改代码 |
| `incident-db-pool-runtime-pressure` | NO_CODE_FIX | HikariCP 连接池耗尽 — 配置/容量问题 |
| `incident-order-submit-5xx-npe` | CODE_FIX | 通用 5xx 带 NPE 证据 |
| `scope-violation-reflection` | STRICT_SINGLE_METHOD | 测试 Guard 拦截 → 反射 → LLM 收敛恢复 |
| `test-assertion-reflection` | FULL_FILE | 测试断言失败 → 反射 → 并发修复恢复 |

## 架构

```
ops-autoagent-api/          API 协议层（DTO）
ops-autoagent-app/          Spring Boot 启动入口
ops-autoagent-domain/       核心 Agent 逻辑
  ├── agent/bugfix/         代码修复 Agent + 提示词
  ├── agent/eval/           评测引擎 + 报告生成器
  ├── agent/orchestrator/   事故修复编排策略
  ├── agent/patch/          PatchScopeGuard、补丁应用、补丁校验
  ├── agent/skill/          7 个工程技能
  ├── agent/llm/            CompatibleChatClient（兼容 OpenAI 协议）
  ├── agent/release/        发布风险 Agent
  ├── agent/test/           测试验证 + 回归测试脚手架
  ├── agent/fixture/        事故 Fixture 证据加载器
  ├── agent/localization/   代码定位 Agent
  └── service/              EngineeringTaskAgentService（任务运行循环）
ops-autoagent-infrastructure/  外部网关（Prometheus、ES、SkyWalking、RAG）
ops-autoagent-trigger/      HTTP 控制器（告警 webhook、评测、任务）
fixtures/                   7 组事故 fixture（告警、指标、日志、链路）
samples/order-service/      评测目标示例项目
```

## 环境要求

- JDK 17
- Maven 3.8+
- OpenAI 兼容的 Chat API（已测试 DeepSeek v4-flash/v4-pro）

可选（实时诊断模式）：
- MySQL 8+
- PostgreSQL with pgvector
- Prometheus、Elasticsearch、SkyWalking

## 构建

```powershell
$env:JAVA_HOME = "D:\Java\jdk17"
mvn -q -DskipTests compile install
```

## 运行

```powershell
$env:JAVA_HOME = "D:\Java\jdk17"
$env:OPENAI_API_KEY = "your-api-key"
$env:OPENAI_BASE_URL = "https://api.deepseek.com"
$env:OPENAI_CHAT_MODEL = "deepseek-v4-flash"

mvn -pl ops-autoagent-app spring-boot:run -Dspring-boot.run.profiles=full
```

## API 端点

### 评测

| 方法 | 路径 | 说明 |
|--------|------|------|
| POST | `/api/v1/codeops/evaluation/run` | 运行全部 7 个内置 case |
| POST | `/api/v1/codeops/evaluation/run/{caseId}` | 运行单个 case |
| GET | `/api/v1/codeops/evaluation/report` | 获取最新结构化评测报告 |

### 任务

| 方法 | 路径 | 说明 |
|--------|------|------|
| GET | `/api/v1/codeops/task/{taskId}` | 获取任务 trace，含所有步骤输出 |

### 告警接入

| 方法 | 路径 | 说明 |
|--------|------|------|
| POST | `/api/v1/ops/alert/webhook/alertmanager` | Alertmanager webhook 接收器 |

## 安全

- 仓库不包含任何 API Key、Token 或密码。
- `.claude/` 已加入 gitignore（本地 Claude Code 配置）。
- 运行时凭据通过环境变量注入。
- `application-full.yml` 使用 `${ENV_VAR:}` 占位符，默认值安全。
