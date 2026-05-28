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
public class CodeOpsTaskTraceStepDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer stepNo;

    private String skillId;

    private String decision;

    private String status;

    private String reason;

    private String summary;

    private List<String> evidence;

    private String phase;

    private Map<String, Object> highlights;

    private Map<String, Object> rawEvidence;

}
