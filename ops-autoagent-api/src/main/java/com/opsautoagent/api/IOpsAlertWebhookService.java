package com.opsautoagent.api;

import com.opsautoagent.api.dto.OpsAlertWebhookAcceptDTO;
import com.opsautoagent.api.dto.OpsAlertWebhookRequestDTO;
import com.opsautoagent.api.response.Response;

/**
 * Ops alert webhook API.
 */
public interface IOpsAlertWebhookService {

    Response<OpsAlertWebhookAcceptDTO> receiveAlertmanagerWebhook(OpsAlertWebhookRequestDTO request);

}

