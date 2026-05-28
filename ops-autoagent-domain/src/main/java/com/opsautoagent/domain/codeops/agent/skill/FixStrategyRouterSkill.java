package com.opsautoagent.domain.codeops.agent.skill;

import com.alibaba.fastjson.JSON;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class FixStrategyRouterSkill implements EngineeringSkill {

    public static final String SKILL_ID = "fix_strategy_router";

    @Override
    public EngineeringSkillEntity metadata() {
        return EngineeringSkillEntity.builder()
                .skillId(SKILL_ID)
                .name("Fix Strategy Router")
                .description("Classify an incident into code fix, config fix, capacity/runtime action, runbook action, or evidence gap before any patch is generated.")
                .supportedTaskTypes(List.of("INCIDENT_TO_FIX"))
                .requiredTools(List.of())
                .riskLevel("READ_ONLY")
                .build();
    }

    @Override
    public EngineeringSkillResultEntity execute(EngineeringTaskEntity task) {
        String evidenceText = evidenceText(task);
        Classification classification = classify(evidenceText);
        Map<String, Object> rawOutput = new LinkedHashMap<>();
        rawOutput.put("phase", "PHASE_FIX_STRATEGY_ROUTING");
        rawOutput.put("strategyType", classification.strategyType());
        rawOutput.put("shouldEnterCodeRepair", classification.shouldEnterCodeRepair());
        rawOutput.put("confidence", classification.confidence());
        rawOutput.put("reasoning", classification.reasoning());
        rawOutput.put("recommendedActions", classification.recommendedActions());
        rawOutput.put("requiredEvidence", classification.requiredEvidence());
        rawOutput.put("guardrail", "Only CODE_FIX may enter automatic code patch generation. Runtime/config/capacity incidents stop before BugFix unless later evidence explicitly points to source code.");

        return EngineeringSkillResultEntity.builder()
                .skillId(SKILL_ID)
                .status("SUCCESS")
                .summary("修复策略路由完成：strategyType=" + classification.strategyType()
                        + "，shouldEnterCodeRepair=" + classification.shouldEnterCodeRepair()
                        + "，confidence=" + classification.confidence())
                .evidence(List.of(
                        "策略类型：" + classification.strategyType(),
                        "是否进入代码修复：" + classification.shouldEnterCodeRepair(),
                        "判断依据：" + String.join("；", classification.reasoning())
                ))
                .nextActions(classification.recommendedActions())
                .rawOutput(rawOutput)
                .build();
    }

    private Classification classify(String evidenceText) {
        String text = value(evidenceText).toLowerCase(Locale.ROOT);
        List<String> reasoning = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        List<String> requiredEvidence = new ArrayList<>();
        boolean codeSignal = containsAny(text,
                "exception", "stacktrace", ".java:", "nullpointerexception", "duplicatekeyexception",
                "illegalstateexception", "indexoutofboundsexception", "submitflashsale", "ordersubmitservice",
                "inventoryservice", "idempotencyservice", "leak", "not closed", "unclosed", "slow sql");
        boolean configSignal = containsAny(text,
                "hikari", "connection pool", "connection_pool", "maxpoolsize", "minimumidle",
                "connection timeout", "pool exhausted", "pool is exhausted");
        boolean runtimeSignal = containsAny(text,
                "jvm", "gc", "full gc", "young gc", "old gen", "heap", "metaspace",
                "cpu", "load average", "thread pool", "queue size", "oom", "outofmemory");
        boolean capacitySignal = containsAny(text,
                "qps", "traffic", "rate limit", "throttle", "saturation", "too many requests",
                "replica", "pod", "scale", "capacity");
        if (codeSignal) {
            reasoning.add("日志/Trace/证据中出现异常栈、源码符号、慢 SQL 或资源泄漏等代码级线索。");
            actions.add("进入 Code Localization 和 Code Repair，生成最小源码修复并执行 compile/test。");
            requiredEvidence.add("真实源码片段");
            requiredEvidence.add("相关测试或最小回归测试");
            return new Classification("CODE_FIX", true, "HIGH", reasoning, actions, requiredEvidence);
        }
        if (configSignal) {
            reasoning.add("证据主要指向连接池参数、连接等待或连接池耗尽，尚未证明是源码缺陷。");
            actions.add("生成连接池配置检查和调整建议，例如 maxPoolSize、connectionTimeout、leakDetectionThreshold。");
            actions.add("继续补证：慢 SQL、连接泄漏栈、数据库连接数、线程等待分布。");
            requiredEvidence.add("Hikari active/max/pending/timeout 指标");
            requiredEvidence.add("数据库连接数和慢 SQL");
            requiredEvidence.add("连接泄漏栈或资源未关闭证据");
            return new Classification("CONFIG_FIX", false, "MEDIUM", reasoning, actions, requiredEvidence);
        }
        if (runtimeSignal) {
            reasoning.add("证据主要指向 JVM/GC/CPU/线程池等运行时资源异常，默认不直接修改业务代码。");
            actions.add("生成运行时处置建议：heap dump、GC log、线程栈、JVM 参数、限流或重启/扩容。");
            actions.add("只有发现对象暴涨来源、死循环、未释放资源等代码证据后才进入 Code Repair。");
            requiredEvidence.add("GC log / heap histogram / thread dump");
            requiredEvidence.add("对象分配热点或线程阻塞栈");
            return new Classification("RUNTIME_ACTION", false, "MEDIUM", reasoning, actions, requiredEvidence);
        }
        if (capacitySignal) {
            reasoning.add("证据主要指向流量或容量压力，优先处置容量、限流和降级。");
            actions.add("生成扩容、限流、降级、队列削峰和容量观察建议。");
            requiredEvidence.add("QPS、错误率、实例负载、队列长度和下游饱和指标");
            return new Classification("CAPACITY_FIX", false, "MEDIUM", reasoning, actions, requiredEvidence);
        }
        reasoning.add("当前证据不足以判断是否为代码缺陷，禁止直接生成源码 patch。");
        actions.add("继续补充日志、Trace、指标和 Runbook 证据。");
        requiredEvidence.add("异常日志或 Trace span");
        requiredEvidence.add("关键指标时间序列");
        return new Classification("NEED_MORE_EVIDENCE", false, "LOW", reasoning, actions, requiredEvidence);
    }

    @SuppressWarnings("unchecked")
    private String evidenceText(EngineeringTaskEntity task) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("goal", task.getGoal());
        values.put("context", task.getContext());
        if (task.getContext() != null) {
            Object skillOutputs = task.getContext().get("skillOutputs");
            if (skillOutputs instanceof Map<?, ?> outputs) {
                values.put("opsDiagnosis", outputs.get(OpsDiagnosisEngineeringSkill.SKILL_ID));
                values.put("codeLocalization", outputs.get(RepoUnderstandingSkill.SKILL_ID));
            }
        }
        return JSON.toJSONString(values);
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private record Classification(String strategyType,
                                  boolean shouldEnterCodeRepair,
                                  String confidence,
                                  List<String> reasoning,
                                  List<String> recommendedActions,
                                  List<String> requiredEvidence) {
    }
}
