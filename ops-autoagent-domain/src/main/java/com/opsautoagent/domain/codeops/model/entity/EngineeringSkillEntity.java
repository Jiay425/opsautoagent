package com.opsautoagent.domain.codeops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EngineeringSkillEntity {

    private String skillId;

    private String name;

    private String description;

    private List<String> supportedTaskTypes;

    private List<String> requiredTools;

    private String riskLevel;

}
