package com.opsautoagent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsAlertWebhookRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String version;

    private String receiver;

    private String status;

    private String groupKey;

    private Map<String, String> commonLabels;

    private Map<String, String> commonAnnotations;

    private String externalURL;

    private List<OpsAlertWebhookAlertDTO> alerts;

}

