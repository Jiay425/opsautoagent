package com.opsautoagent.domain.ops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsServiceOwnerEntity {

    private Long id;

    private String serviceName;

    private String ownerName;

    private String ownerEmail;

    private String ownerWecom;

    private String ownerDingTalk;

    private String backupOwnerEmail;

    private Boolean enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}

