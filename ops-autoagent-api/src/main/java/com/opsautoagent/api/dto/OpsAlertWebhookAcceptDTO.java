package com.opsautoagent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsAlertWebhookAcceptDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer totalAlerts;

    private Integer acceptedCount;

    private Integer skippedCount;

    private String message;

}

