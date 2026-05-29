package com.opsautoagent.trigger.http;

import com.opsautoagent.api.IOpsAlertWebhookService;
import com.opsautoagent.api.dto.OpsAlertWebhookAcceptDTO;
import com.opsautoagent.api.dto.OpsAlertWebhookRequestDTO;
import com.opsautoagent.api.response.Response;
import com.opsautoagent.domain.ops.model.valobj.alert.OpsAlertIngestResult;
import com.opsautoagent.domain.ops.service.OpsSensitiveDataMasker;
import com.opsautoagent.domain.codeops.agent.scheduler.IncidentScheduler;
import com.opsautoagent.domain.ops.service.alert.OpsAlertIngestService;
import com.opsautoagent.types.enums.ResponseCode;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/ops/alert/webhook")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.POST, RequestMethod.OPTIONS})
public class OpsAlertWebhookController implements IOpsAlertWebhookService {

    @Resource
    private OpsAlertIngestService opsAlertIngestService;

    @Resource
    private OpsApiGuard opsApiGuard;

    @Resource
    private OpsSensitiveDataMasker sensitiveDataMasker;

    @Resource
    private IncidentScheduler incidentScheduler;

    @Override
    @RequestMapping(value = "/alertmanager", method = RequestMethod.POST)
    public Response<OpsAlertWebhookAcceptDTO> receiveAlertmanagerWebhook(@RequestBody OpsAlertWebhookRequestDTO request) {
        String sessionId = UUID.randomUUID().toString();
        if (request == null || request.getAlerts() == null || request.getAlerts().isEmpty()) {
            return Response.<OpsAlertWebhookAcceptDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("alert webhook payload cannot be empty")
                    .build();
        }

        String guardMessage = opsApiGuard.checkAlertWebhookRequest(request, sessionId);
        if (guardMessage != null) {
            return Response.<OpsAlertWebhookAcceptDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(guardMessage)
                    .build();
        }

        log.info("Ops alert webhook received. sessionId={}, payload={}",
                sessionId, sensitiveDataMasker.mask(JSON.toJSONString(request)));

        OpsAlertIngestResult result = opsAlertIngestService.ingestAlertmanager(request);

        // Route to scheduler for dedup + queueing (non-blocking, no impact on webhook response)
        if (request.getAlerts() != null) {
            for (var alert : request.getAlerts()) {
                try {
                    Map<String, String> labels = alert.getLabels() != null ? alert.getLabels() : Map.of();
                    Map<String, String> annotations = alert.getAnnotations() != null ? alert.getAnnotations() : Map.of();
                    incidentScheduler.ingest(
                            alert.getFingerprint(),
                            labels.getOrDefault("alertname", "unknown"),
                            labels.getOrDefault("service", "unknown"),
                            labels.getOrDefault("severity", "warning"),
                            annotations.getOrDefault("summary", annotations.getOrDefault("description", "")),
                            labels.getOrDefault("endpoint", "")
                    );
                } catch (Exception ignored) {
                }
            }
        }

        return Response.<OpsAlertWebhookAcceptDTO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(OpsAlertWebhookAcceptDTO.builder()
                        .totalAlerts(result.getTotalAlerts())
                        .acceptedCount(result.getAcceptedCount())
                        .skippedCount(result.getSkippedCount())
                        .message("alert webhook accepted")
                        .build())
                .build();
    }

}

