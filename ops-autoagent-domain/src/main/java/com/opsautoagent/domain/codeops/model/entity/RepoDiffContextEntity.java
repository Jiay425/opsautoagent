package com.opsautoagent.domain.codeops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RepoDiffContextEntity {

    private String repositoryPath;

    private String changeRef;

    private List<String> changedFiles;

    private List<String> relatedTestFiles;

    private List<RepoDiffHunkEntity> hunks;

    private String diffSummary;

    private String diffText;

    private Boolean diffAvailable;

    private String errorMessage;

}
