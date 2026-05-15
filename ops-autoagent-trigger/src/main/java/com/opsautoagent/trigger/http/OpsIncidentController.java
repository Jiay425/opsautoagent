package com.opsautoagent.trigger.http;

import com.opsautoagent.api.IOpsIncidentService;
import com.opsautoagent.api.dto.OpsIncidentAnalyzeRequestDTO;
import com.opsautoagent.api.dto.OpsIncidentDiagnosisRecordDTO;
import com.opsautoagent.api.response.Response;
import com.opsautoagent.domain.ops.adapter.repository.IOpsIncidentRepository;
import com.opsautoagent.domain.ops.model.entity.DiagnosisRecordEntity;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAnalyzeEventEntity;
import com.opsautoagent.domain.ops.service.OpsIncidentExecuteStrategy;
import com.opsautoagent.domain.ops.service.OpsSensitiveDataMasker;
import com.opsautoagent.types.enums.ResponseCode;
import com.alibaba.fastjson.JSON;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@RestController
@RequestMapping("/api/v1/ops/incident")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class OpsIncidentController implements IOpsIncidentService {

    @Resource
    private OpsIncidentExecuteStrategy opsIncidentExecuteStrategy;

    @Resource
    private IOpsIncidentRepository opsIncidentRepository;

    @Resource
    private OpsApiGuard opsApiGuard;

    @Resource
    private OpsSensitiveDataMasker sensitiveDataMasker;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Override
    @RequestMapping(value = "analyze", method = RequestMethod.POST)
    public ResponseBodyEmitter analyzeIncident(@RequestBody OpsIncidentAnalyzeRequestDTO request, HttpServletResponse response) {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        String sessionId = UUID.randomUUID().toString();
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(10 * 60 * 1000L);

        String validationMessage = validate(request);
        if (validationMessage != null) {
            sendError(emitter, validationMessage, sessionId);
            return emitter;
        }

        String guardMessage = opsApiGuard.checkAnalyzeRequest(request, sessionId);
        if (guardMessage != null) {
            sendError(emitter, guardMessage, sessionId);
            return emitter;
        }

        IncidentCommandEntity command = IncidentCommandEntity.builder()
                .serviceName(request.getServiceName())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .problem(request.getProblem())
                .traceId(request.getTraceId())
                .maxStep(request.getMaxStep() == null ? 6 : request.getMaxStep())
                .sessionId(sessionId)
                .build();

        log.info("Ops incident diagnosis request started. sessionId={}, request={}",
                sessionId, sensitiveDataMasker.mask(JSON.toJSONString(request)));

        threadPoolExecutor.execute(() -> {
            try {
                opsIncidentExecuteStrategy.execute(command, emitter);
            } catch (Exception e) {
                log.error("Ops incident diagnosis failed. sessionId={}", sessionId, e);
                sendError(emitter, "Ops incident diagnosis failed: " + e.getMessage(), sessionId);
            } finally {
                emitter.complete();
            }
        });

        return emitter;
    }

    @Override
    @RequestMapping(value = "record/{diagnosisId}", method = RequestMethod.GET)
    public Response<OpsIncidentDiagnosisRecordDTO> queryDiagnosisRecord(@PathVariable("diagnosisId") String diagnosisId) {
        if (isBlank(diagnosisId)) {
            return Response.<OpsIncidentDiagnosisRecordDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("diagnosisId cannot be blank")
                    .build();
        }

        String sessionId = UUID.randomUUID().toString();
        String guardMessage = opsApiGuard.checkRecordQuery(diagnosisId, sessionId);
        if (guardMessage != null) {
            return Response.<OpsIncidentDiagnosisRecordDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(guardMessage)
                    .build();
        }

        DiagnosisRecordEntity record = opsIncidentRepository.queryDiagnosisRecord(diagnosisId);
        if (record == null) {
            return Response.<OpsIncidentDiagnosisRecordDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("diagnosis record not found")
                    .build();
        }

        return Response.<OpsIncidentDiagnosisRecordDTO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(toDTO(record))
                .build();
    }

    private OpsIncidentDiagnosisRecordDTO toDTO(DiagnosisRecordEntity record) {
        return OpsIncidentDiagnosisRecordDTO.builder()
                .diagnosisId(record.getDiagnosisId())
                .sessionId(record.getSessionId())
                .serviceName(record.getServiceName())
                .startTime(record.getStartTime())
                .endTime(record.getEndTime())
                .problem(record.getProblem())
                .traceId(record.getTraceId())
                .status(record.getStatus())
                .requestJson(record.getRequestJson())
                .metricEvidenceJson(record.getMetricEvidenceJson())
                .logEvidenceJson(record.getLogEvidenceJson())
                .traceEvidenceJson(record.getTraceEvidenceJson())
                .evidenceChainJson(record.getEvidenceChainJson())
                .runbookJson(record.getRunbookJson())
                .report(record.getReport())
                .errorMessage(record.getErrorMessage())
                .createTime(record.getCreateTime() == null ? null : record.getCreateTime().toString())
                .updateTime(record.getUpdateTime() == null ? null : record.getUpdateTime().toString())
                .build();
    }

    private String validate(OpsIncidentAnalyzeRequestDTO request) {
        if (request == null) {
            return "request body cannot be null";
        }
        if (isBlank(request.getServiceName())) {
            return "serviceName cannot be blank";
        }
        if (isBlank(request.getStartTime())) {
            return "startTime cannot be blank";
        }
        if (isBlank(request.getEndTime())) {
            return "endTime cannot be blank";
        }
        if (isBlank(request.getProblem())) {
            return "problem cannot be blank";
        }
        if (request.getProblem().length() > 2000) {
            return "problem length must be <= 2000";
        }
        if (request.getMaxStep() != null && (request.getMaxStep() < 1 || request.getMaxStep() > 10)) {
            return "maxStep must be between 1 and 10";
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void sendError(ResponseBodyEmitter emitter, String message, String sessionId) {
        try {
            emitter.send("data: " + JSON.toJSONString(OpsAnalyzeEventEntity.error(sensitiveDataMasker.mask(message), sessionId)) + "\n\n");
        } catch (IOException e) {
            log.warn("Send ops diagnosis error event failed. sessionId={}", sessionId, e);
        } finally {
            emitter.complete();
        }
    }

}

