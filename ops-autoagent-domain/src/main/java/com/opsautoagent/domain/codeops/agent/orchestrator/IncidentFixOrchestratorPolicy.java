package com.opsautoagent.domain.codeops.agent.orchestrator;

import com.opsautoagent.domain.codeops.agent.skill.BugFixSkill;
import com.opsautoagent.domain.codeops.agent.skill.AgentLoopEngineeringSkill;
import com.opsautoagent.domain.codeops.agent.skill.EngineeringKnowledgeRagSkill;
import com.opsautoagent.domain.codeops.agent.skill.OpsDiagnosisEngineeringSkill;
import com.opsautoagent.domain.codeops.agent.skill.PrReviewSkill;
import com.opsautoagent.domain.codeops.agent.skill.ReleaseRiskSkill;
import com.opsautoagent.domain.codeops.agent.skill.RepoUnderstandingSkill;
import com.opsautoagent.domain.codeops.agent.skill.TestVerificationSkill;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskStepEntity;
import com.opsautoagent.domain.codeops.model.entity.IncidentFixWorkingMemory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class IncidentFixOrchestratorPolicy {

    public IncidentFixOrchestratorDecision decide(EngineeringTaskEntity task, IncidentFixWorkingMemory memory) {
        String taskType = task.getTaskType();
        if ("INCIDENT_TO_FIX".equals(taskType)) {
            return decideIncidentToFix(task, memory);
        }
        if ("ISSUE_TO_PATCH".equals(taskType)) {
            return decideIssueToPatch(task, memory);
        }
        if ("RELEASE_RISK".equals(taskType)) {
            return decideReleaseRisk(task, memory);
        }
        return decideCodeReview(task, memory);
    }

    private IncidentFixOrchestratorDecision decideIncidentToFix(EngineeringTaskEntity task, IncidentFixWorkingMemory memory) {
        if (Boolean.TRUE.equals(contextValue(task, "incidentFixReflectionExhausted"))) {
            if (needsMapStage(memory.getReleaseRisk(), task, ReleaseRiskSkill.SKILL_ID)) {
                return IncidentFixOrchestratorDecision.call(ReleaseRiskSkill.SKILL_ID,
                        "测试验证连续失败已达到 3 轮反思上限，进入失败态发布风险分析，输出当前 patch 可信度、失败日志、人工接管点、上线观察和回滚建议。");
            }
            return IncidentFixOrchestratorDecision.stop("测试验证连续失败已达到 3 轮反思上限，已生成失败态发布风险分析，等待人工查看失败日志和当前 patch。");
        }
        if (needsMapStage(memory.getOpsEvidence(), task, OpsDiagnosisEngineeringSkill.SKILL_ID)) {
            return IncidentFixOrchestratorDecision.call(OpsDiagnosisEngineeringSkill.SKILL_ID,
                    "线上告警修复任务需要先形成运维证据，供后续代码定位和修复 Agent 使用。");
        }
        if (needsAgentLoopInvestigation(task, memory)) {
            return IncidentFixOrchestratorDecision.call(AgentLoopEngineeringSkill.SKILL_ID,
                    "已有运维证据，先通过模型驱动工具循环做一次只读仓库调查，为后续代码定位和修复策略提供上下文。");
        }
        if (needsMapStage(memory.getCodeLocalization(), task, RepoUnderstandingSkill.SKILL_ID)
                || needsRepoUnderstandingAfterAgentLoop(task, memory)) {
            return IncidentFixOrchestratorDecision.call(RepoUnderstandingSkill.SKILL_ID,
                    "Agent loop 调查结果仍需补强，下一步由 Incident Triage Agent 判断是否该改代码，并在需要时定位可疑文件和方法。");
        }
        if (!shouldEnterCodeRepair(task, memory)) {
            if (needsMapStage(memory.getReleaseRisk(), task, ReleaseRiskSkill.SKILL_ID)) {
                return IncidentFixOrchestratorDecision.call(ReleaseRiskSkill.SKILL_ID,
                        "Incident Triage 判断当前不应自动改代码，生成运行时/配置/容量处置建议、观察指标和人工确认点。");
            }
            return IncidentFixOrchestratorDecision.stop("Incident Triage 判断当前事故不应进入自动代码修复，已输出处置建议和风险观察项。");
        }
        if (needsMapStage(memory.getEngineeringKnowledge(), task, EngineeringKnowledgeRagSkill.SKILL_ID)) {
            return IncidentFixOrchestratorDecision.call(EngineeringKnowledgeRagSkill.SKILL_ID,
                    "需要补充工程知识和 Runbook 背景，作为修复生成 Agent 的参考证据。");
        }
        if (isReflectionActive(task) && isEmpty(memory.getPatchGeneration())) {
            return IncidentFixOrchestratorDecision.call(BugFixSkill.SKILL_ID,
                    "测试验证失败后进入反思修复轮，将失败日志回灌给修复生成 Agent，重新生成或调整 patch。");
        }
        if (isReflectionActive(task) && isEmpty(memory.getTestVerification())) {
            return IncidentFixOrchestratorDecision.call(TestVerificationSkill.SKILL_ID,
                    "反思修复轮已有新的修复上下文，重新生成测试补丁并执行验证。");
        }
        if (isReflectionActive(task) && isEmpty(memory.getReleaseRisk())) {
            return IncidentFixOrchestratorDecision.call(ReleaseRiskSkill.SKILL_ID,
                    "反思修复轮测试已完成，重新评估发布风险和上线观察项。");
        }
        if (needsMapStage(memory.getPatchGeneration(), task, BugFixSkill.SKILL_ID)) {
            return IncidentFixOrchestratorDecision.call(BugFixSkill.SKILL_ID,
                    "已有故障证据、代码候选和知识上下文，下一步由修复生成 Agent 产出最小 patch。");
        }
        if (isNoCodeFix(memory.getPatchGeneration())) {
            if (needsMapStage(memory.getReleaseRisk(), task, ReleaseRiskSkill.SKILL_ID)) {
                return IncidentFixOrchestratorDecision.call(ReleaseRiskSkill.SKILL_ID,
                        "修复生成 Agent 判断当前事故不需要代码补丁，跳过测试验证，直接输出运行时处置建议和上线观察项。");
            }
            return IncidentFixOrchestratorDecision.stop("当前事故已判定为非代码修复场景，已输出运行时处置建议和风险观察项。");
        }
        if (needsMapStage(memory.getTestVerification(), task, TestVerificationSkill.SKILL_ID)) {
            return IncidentFixOrchestratorDecision.call(TestVerificationSkill.SKILL_ID,
                    "已有修复产物或已尝试修复生成，下一步由测试验证 Agent 决定并执行相关验证。");
        }
        if (needsMapStage(memory.getReleaseRisk(), task, ReleaseRiskSkill.SKILL_ID)) {
            return IncidentFixOrchestratorDecision.call(ReleaseRiskSkill.SKILL_ID,
                    "已有测试验证上下文，下一步由发布风险 Agent 评估上线观察项、回滚点和剩余风险。");
        }
        return IncidentFixOrchestratorDecision.stop("Incident-to-Fix 所需的运维证据、代码定位、知识补充、修复、测试和发布风险阶段均已完成或已尝试。");
    }

    private IncidentFixOrchestratorDecision decideIssueToPatch(EngineeringTaskEntity task, IncidentFixWorkingMemory memory) {
        if (needsAgentLoopInvestigation(task, memory)) {
            return IncidentFixOrchestratorDecision.call(AgentLoopEngineeringSkill.SKILL_ID,
                    "需求到修复任务先通过模型驱动工具循环做只读仓库调查，定位候选代码和测试文件。");
        }
        if (needsMapStage(memory.getCodeLocalization(), task, RepoUnderstandingSkill.SKILL_ID)
                || needsRepoUnderstandingAfterAgentLoop(task, memory)) {
            return IncidentFixOrchestratorDecision.call(RepoUnderstandingSkill.SKILL_ID,
                    "Agent loop 未形成足够稳定的候选代码位置，继续由仓库理解 Agent 补充定位。");
        }
        if (!shouldEnterCodeRepair(task, memory)) {
            return IncidentFixOrchestratorDecision.stop("Agent loop / 代码定位判断当前不应进入自动代码修复，任务停止等待人工确认或补充证据。");
        }
        if (needsMapStage(memory.getEngineeringKnowledge(), task, EngineeringKnowledgeRagSkill.SKILL_ID)) {
            return IncidentFixOrchestratorDecision.call(EngineeringKnowledgeRagSkill.SKILL_ID,
                    "需要补充工程知识，避免修复建议脱离项目约束。");
        }
        if (needsMapStage(memory.getPatchGeneration(), task, BugFixSkill.SKILL_ID)) {
            return IncidentFixOrchestratorDecision.call(BugFixSkill.SKILL_ID,
                    "已有代码上下文和知识上下文，下一步生成修复 patch。");
        }
        if (needsMapStage(memory.getTestVerification(), task, TestVerificationSkill.SKILL_ID)) {
            return IncidentFixOrchestratorDecision.call(TestVerificationSkill.SKILL_ID,
                    "已有修复产物，下一步生成并执行测试验证计划。");
        }
        if (needsMapStage(memory.getReleaseRisk(), task, ReleaseRiskSkill.SKILL_ID)) {
            return IncidentFixOrchestratorDecision.call(ReleaseRiskSkill.SKILL_ID,
                    "已有修复和测试上下文，最后评估发布风险、回归重点和人工确认点。");
        }
        return IncidentFixOrchestratorDecision.stop("Issue-to-Patch 的代码定位、知识补充、修复、测试和发布风险阶段均已完成或已尝试。");
    }

    private IncidentFixOrchestratorDecision decideReleaseRisk(EngineeringTaskEntity task, IncidentFixWorkingMemory memory) {
        if (needsAgentLoopInvestigation(task, memory)) {
            return IncidentFixOrchestratorDecision.call(AgentLoopEngineeringSkill.SKILL_ID,
                    "发布风险评估先通过模型驱动工具循环做只读仓库调查，补充变更相关代码和测试上下文。");
        }
        if (needsMapStage(memory.getCodeLocalization(), task, RepoUnderstandingSkill.SKILL_ID)
                || needsRepoUnderstandingAfterAgentLoop(task, memory)) {
            return IncidentFixOrchestratorDecision.call(RepoUnderstandingSkill.SKILL_ID,
                    "Agent loop 调查结果还不足以支撑发布风险判断，继续补充变更涉及的代码区域。");
        }
        if (needsMapStage(memory.getEngineeringKnowledge(), task, EngineeringKnowledgeRagSkill.SKILL_ID)) {
            return IncidentFixOrchestratorDecision.call(EngineeringKnowledgeRagSkill.SKILL_ID,
                    "需要补充发布规范、Runbook 或工程知识。");
        }
        if (needsMapStage(memory.getReleaseRisk(), task, ReleaseRiskSkill.SKILL_ID)) {
            return IncidentFixOrchestratorDecision.call(ReleaseRiskSkill.SKILL_ID,
                    "已有代码和知识上下文，下一步评估发布风险。");
        }
        if (needsMapStage(memory.getTestVerification(), task, TestVerificationSkill.SKILL_ID)) {
            return IncidentFixOrchestratorDecision.call(TestVerificationSkill.SKILL_ID,
                    "发布风险评估后需要补充验证计划和测试结果。");
        }
        return IncidentFixOrchestratorDecision.stop("Release-Risk 的代码理解、知识补充、风险评估和测试验证阶段均已完成或已尝试。");
    }

    private IncidentFixOrchestratorDecision decideCodeReview(EngineeringTaskEntity task, IncidentFixWorkingMemory memory) {
        if (needsAgentLoopInvestigation(task, memory)) {
            return IncidentFixOrchestratorDecision.call(AgentLoopEngineeringSkill.SKILL_ID,
                    "代码审查任务先通过模型驱动工具循环做只读仓库调查，补充候选文件、测试和风险上下文。");
        }
        if (needsMapStage(memory.getCodeLocalization(), task, RepoUnderstandingSkill.SKILL_ID)
                || needsRepoUnderstandingAfterAgentLoop(task, memory)) {
            return IncidentFixOrchestratorDecision.call(RepoUnderstandingSkill.SKILL_ID,
                    "Agent loop 调查结果还不足以支撑代码审查，继续补充变更和仓库上下文。");
        }
        if (needsMapStage(memory.getEngineeringKnowledge(), task, EngineeringKnowledgeRagSkill.SKILL_ID)) {
            return IncidentFixOrchestratorDecision.call(EngineeringKnowledgeRagSkill.SKILL_ID,
                    "需要补充工程知识，提升审查判断的项目贴合度。");
        }
        if (!hasExecuted(task, PrReviewSkill.SKILL_ID)) {
            return IncidentFixOrchestratorDecision.call(PrReviewSkill.SKILL_ID,
                    "已有代码上下文和知识上下文，下一步由代码审查 Agent 分析风险。");
        }
        if (needsMapStage(memory.getTestVerification(), task, TestVerificationSkill.SKILL_ID)) {
            return IncidentFixOrchestratorDecision.call(TestVerificationSkill.SKILL_ID,
                    "代码审查后需要补充验证计划。");
        }
        return IncidentFixOrchestratorDecision.stop("Code-Review 的代码理解、知识补充、审查和测试验证阶段均已完成或已尝试。");
    }

    private boolean needsMapStage(Map<String, Object> value, EngineeringTaskEntity task, String skillId) {
        return isEmpty(value) && !hasExecuted(task, skillId);
    }

    private boolean needsAgentLoopInvestigation(EngineeringTaskEntity task, IncidentFixWorkingMemory memory) {
        if (hasExecuted(task, AgentLoopEngineeringSkill.SKILL_ID)) {
            return false;
        }
        if (Boolean.FALSE.equals(contextValue(task, "agentLoopInvestigationEnabled"))) {
            return false;
        }
        if ("INCIDENT_TO_FIX".equals(task.getTaskType())) {
            return memory != null && !isEmpty(memory.getOpsEvidence()) && isEmpty(memory.getCodeLocalization());
        }
        return isEmpty(memory == null ? null : memory.getCodeLocalization());
    }

    private boolean needsRepoUnderstandingAfterAgentLoop(EngineeringTaskEntity task, IncidentFixWorkingMemory memory) {
        if (!hasExecuted(task, AgentLoopEngineeringSkill.SKILL_ID) || hasExecuted(task, RepoUnderstandingSkill.SKILL_ID)) {
            return false;
        }
        if (memory == null || isEmpty(memory.getCodeLocalization())) {
            return false;
        }
        return hasLowLocalizationConfidence(memory) || isEmptyListValue(memory.getCodeLocalization().get("targetFiles"));
    }

    private boolean isEmpty(Map<String, Object> value) {
        return value == null || value.isEmpty();
    }

    private boolean shouldEnterCodeRepair(EngineeringTaskEntity task, IncidentFixWorkingMemory memory) {
        if (Boolean.TRUE.equals(contextValue(task, "allowPatchApply"))
                || Boolean.TRUE.equals(contextValue(task, "allowTestPatchApply"))
                || containsFocusArea(task, "bug_fix")
                || containsFocusArea(task, "test_verification")) {
            return true;
        }
        if (hasIncidentCodeEvidence(memory)) {
            return true;
        }
        if (memory != null && hasExecuted(task, AgentLoopEngineeringSkill.SKILL_ID)
                && hasLowLocalizationConfidence(memory)
                && isEmptyListValue(memory.getCodeLocalization().get("targetFiles"))) {
            return false;
        }
        Map<String, Object> strategy = memory.getFixStrategy();
        if (strategy == null || strategy.isEmpty()) {
            return true;
        }
        Object value = strategy.get("shouldEnterCodeRepair");
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private boolean hasLowLocalizationConfidence(IncidentFixWorkingMemory memory) {
        if (memory == null || memory.getCodeLocalization() == null) {
            return false;
        }
        Object value = memory.getCodeLocalization().get("localizationConfidence");
        return "LOW".equalsIgnoreCase(String.valueOf(value));
    }

    private boolean isEmptyListValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        return String.valueOf(value).isBlank();
    }

    @SuppressWarnings("unchecked")
    private boolean isNoCodeFix(Map<String, Object> patchGeneration) {
        if (patchGeneration == null || patchGeneration.isEmpty()) {
            return false;
        }
        if ("BUG_FIX_SKIPPED_NO_CODE_FIX".equals(String.valueOf(patchGeneration.get("phase")))) {
            return true;
        }
        Object repairScope = patchGeneration.get("repairScope");
        if (repairScope instanceof Map<?, ?> scope) {
            Object scopeType = scope.get("scopeType");
            return "NO_CODE_FIX".equals(String.valueOf(scopeType));
        }
        return false;
    }

    private boolean containsFocusArea(EngineeringTaskEntity task, String focusArea) {
        List<String> focusAreas = task.getFocusAreas();
        if (focusAreas == null || focusAreas.isEmpty()) {
            return false;
        }
        return focusAreas.stream().anyMatch(value -> focusArea.equalsIgnoreCase(String.valueOf(value)));
    }

    private boolean hasIncidentCodeEvidence(IncidentFixWorkingMemory memory) {
        if (memory == null) {
            return false;
        }
        String text = String.valueOf(memory.getOpsEvidence()) + "\n"
                + String.valueOf(memory.getCodeHints()) + "\n"
                + String.valueOf(memory.getCodeLocalization());
        String lower = text.toLowerCase();
        return (lower.contains(".java") || lower.contains("controller."))
                && (lower.contains("exception") || lower.contains("stack") || lower.contains("at com.")
                || lower.contains("negative") || lower.contains("duplicate"));
    }

    private boolean hasExecuted(EngineeringTaskEntity task, String skillId) {
        List<EngineeringTaskStepEntity> steps = task.getSteps();
        if (steps == null || steps.isEmpty()) {
            return false;
        }
        return steps.stream().anyMatch(step -> skillId.equals(step.getSelectedSkill()));
    }

    private Object contextValue(EngineeringTaskEntity task, String key) {
        if (task.getContext() == null) {
            return null;
        }
        return task.getContext().get(key);
    }

    private boolean isReflectionActive(EngineeringTaskEntity task) {
        Object value = contextValue(task, "incidentFixReflectionRound");
        if (value instanceof Number number) {
            return number.intValue() > 0;
        }
        try {
            return value != null && Integer.parseInt(String.valueOf(value)) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

}
