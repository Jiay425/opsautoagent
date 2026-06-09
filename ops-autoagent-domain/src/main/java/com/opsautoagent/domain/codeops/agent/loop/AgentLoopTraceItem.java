package com.opsautoagent.domain.codeops.agent.loop;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentLoopTraceItem {

    private int turnNo;

    private String toolName;

    private String permission;

    private String toolStatus;

    private String summary;

    private String outputPreview;

}
