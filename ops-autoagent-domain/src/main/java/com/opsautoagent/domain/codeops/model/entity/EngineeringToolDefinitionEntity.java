package com.opsautoagent.domain.codeops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EngineeringToolDefinitionEntity {

    private String toolName;

    private String description;

    private String category;

    private String riskLevel;

    private String accessLevel;

    private String sourceType;

    private Integer timeoutMillis;

    private Boolean enabled;

    private Map<String, Object> argumentSchema;

}
