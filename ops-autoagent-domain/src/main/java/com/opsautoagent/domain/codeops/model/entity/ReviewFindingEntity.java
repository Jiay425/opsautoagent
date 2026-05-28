package com.opsautoagent.domain.codeops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReviewFindingEntity {

    private String severity;

    private String category;

    private String location;

    private String filePath;

    private Integer startLine;

    private Integer endLine;

    private String title;

    private String detail;

    private String recommendation;

}
