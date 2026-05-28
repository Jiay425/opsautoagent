package com.opsautoagent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Skill metadata exposed by CodeOps Agent.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeOpsSkillDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String skillId;

    private String name;

    private String description;

    private List<String> supportedTaskTypes;

    private List<String> requiredTools;

    private String riskLevel;

}
