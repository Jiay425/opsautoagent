package com.opsautoagent.domain.codeops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EngineeringSkillResultEntity {

    private String skillId;

    private String status;

    private String summary;

    private List<String> evidence;

    private List<String> nextActions;

    private Map<String, Object> rawOutput;

}
