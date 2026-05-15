package com.opsautoagent.domain.ops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EvidenceItemEntity {

    /**
     * prometheus, elk, skywalking, user_input, system.
     */
    private String source;

    /**
     * metric, log, trace, context.
     */
    private String category;

    private String title;

    private String detail;

    /**
     * 0-100 confidence score for this evidence item.
     */
    private Integer confidence;

}

