package com.opsautoagent.domain.codeops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EngineeringKnowledgeMatchEntity {

    private String documentId;

    private String title;

    private String category;

    private Integer score;

    private String path;

    private String summary;

    private String content;

}
