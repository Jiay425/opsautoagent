package com.opsautoagent.domain.ops.agent.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsAgentSkill {

    private String skillId;

    private String name;

    private String category;

    private List<String> matchedAlertRules;

    private List<String> symptoms;

    private List<String> recommendedTools;

    private List<String> keyMetrics;

    private List<String> logPatterns;

    private List<String> tracePatterns;

    private List<String> rootCauseRules;

    private List<String> temporaryFixes;

    private List<String> longTermFixes;

    private String runbookPath;

    private String content;

    private Integer score;

}

