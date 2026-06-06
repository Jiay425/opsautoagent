package com.opsautoagent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeOpsIncidentFixStageDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String stageId;

    private String stageName;

    private String status;

    private List<String> skillIds;

    private Integer stepNo;

    private String summary;

    private List<String> evidence;

    private Map<String, Object> keyArtifacts;

}
