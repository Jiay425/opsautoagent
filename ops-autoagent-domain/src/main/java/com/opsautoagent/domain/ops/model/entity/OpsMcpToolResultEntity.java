package com.opsautoagent.domain.ops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsMcpToolResultEntity {

    private String mcpId;

    private String toolName;

    private boolean success;

    private boolean toolError;

    private String content;

    private String errorMessage;

    private long costMillis;

}

