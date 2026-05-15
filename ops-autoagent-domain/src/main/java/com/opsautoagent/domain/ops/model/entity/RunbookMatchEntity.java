package com.opsautoagent.domain.ops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RunbookMatchEntity {

    private String runbookId;

    private String title;

    private String category;

    /**
     * 0-100 keyword retrieval score.
     */
    private Integer score;

    private String path;

    private String summary;

    private String content;

}

