package com.opsautoagent.domain.codeops.agent.skill;

import com.opsautoagent.domain.codeops.agent.knowledge.EngineeringKnowledgeSearchService;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringKnowledgeMatchEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class EngineeringKnowledgeRagSkill implements EngineeringSkill {

    public static final String SKILL_ID = "engineering_knowledge_rag";

    private final EngineeringKnowledgeSearchService knowledgeSearchService;

    public EngineeringKnowledgeRagSkill(EngineeringKnowledgeSearchService knowledgeSearchService) {
        this.knowledgeSearchService = knowledgeSearchService;
    }

    @Override
    public EngineeringSkillEntity metadata() {
        return EngineeringSkillEntity.builder()
                .skillId(SKILL_ID)
                .name("Engineering Knowledge RAG Skill")
                .description("Retrieve engineering docs, runbooks, review rules and historical postmortems.")
                .supportedTaskTypes(List.of("CODE_REVIEW", "ISSUE_TO_PATCH", "INCIDENT_TO_FIX", "RELEASE_RISK"))
                .requiredTools(List.of("knowledge.search"))
                .riskLevel("READ_ONLY")
                .build();
    }

    @Override
    public EngineeringSkillResultEntity execute(EngineeringTaskEntity task) {
        List<EngineeringKnowledgeMatchEntity> matches = knowledgeSearchService.search(task, List.of(), List.of(), 5);
        return EngineeringSkillResultEntity.builder()
                .skillId(SKILL_ID)
                .status("SUCCESS")
                .summary("已完成工程知识检索：命中 " + matches.size() + " 条文档证据。")
                .evidence(buildEvidence(task, matches))
                .nextActions(List.of("将工程知识注入 PR Review LLM", "后续接入 PgVector 向量召回", "增加文档类型 metadata 与引用评测"))
                .rawOutput(Map.of(
                        "phase", "PHASE_3_ENGINEERING_KNOWLEDGE",
                        "matches", matches
                ))
                .build();
    }

    private List<String> buildEvidence(EngineeringTaskEntity task, List<EngineeringKnowledgeMatchEntity> matches) {
        List<String> evidence = new java.util.ArrayList<>();
        evidence.add("检索目标：" + task.getGoal());
        evidence.add("知识范围：README、架构文档、编码规范、发布规范、历史事故复盘、Runbook");
        if (matches == null || matches.isEmpty()) {
            evidence.add("未命中工程知识文档");
            return evidence;
        }
        for (EngineeringKnowledgeMatchEntity match : matches) {
            evidence.add("[" + match.getScore() + "][" + match.getCategory() + "] "
                    + match.getTitle() + " -> " + match.getPath());
        }
        return evidence;
    }

}
