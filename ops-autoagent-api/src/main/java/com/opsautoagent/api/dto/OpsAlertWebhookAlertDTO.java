package com.opsautoagent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsAlertWebhookAlertDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String status;

    private Map<String, String> labels;

    private Map<String, String> annotations;

    private String startsAt;

    private String endsAt;

    private String fingerprint;

    private String generatorURL;

}

