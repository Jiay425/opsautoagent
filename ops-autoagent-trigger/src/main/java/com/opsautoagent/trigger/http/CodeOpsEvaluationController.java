package com.opsautoagent.trigger.http;

import com.opsautoagent.api.response.Response;
import com.opsautoagent.domain.codeops.agent.eval.CodeOpsEvalReport;
import com.opsautoagent.domain.codeops.agent.eval.CodeOpsEvalSummary;
import com.opsautoagent.domain.codeops.agent.eval.CodeOpsEvaluationService;
import com.opsautoagent.domain.codeops.agent.llm.ModelRouter;
import com.opsautoagent.domain.codeops.agent.scheduler.IncidentScheduler;
import com.opsautoagent.domain.codeops.agent.security.HumanApprovalGate;
import com.opsautoagent.domain.codeops.service.EngineeringTaskAgentService;
import com.opsautoagent.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/codeops/evaluation")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class CodeOpsEvaluationController {

    @Resource
    private CodeOpsEvaluationService codeOpsEvaluationService;

    @Resource
    private HumanApprovalGate humanApprovalGate;

    @Resource
    private EngineeringTaskAgentService engineeringTaskAgentService;

    @Resource
    private IncidentScheduler incidentScheduler;

    @Resource
    private ModelRouter modelRouter;

    @RequestMapping(value = "run", method = RequestMethod.POST)
    public Response<CodeOpsEvalSummary> runEvaluation() {
        try {
            CodeOpsEvalSummary summary = codeOpsEvaluationService.runBuiltinCases();
            return Response.<CodeOpsEvalSummary>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(summary)
                    .build();
        } catch (Exception e) {
            log.warn("CodeOps evaluation run failed.", e);
            return Response.<CodeOpsEvalSummary>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "run/{caseId}", method = RequestMethod.POST)
    public Response<CodeOpsEvalSummary> runEvaluationCase(@PathVariable("caseId") String caseId) {
        try {
            CodeOpsEvalSummary summary = codeOpsEvaluationService.runBuiltinCase(caseId);
            return Response.<CodeOpsEvalSummary>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(summary)
                    .build();
        } catch (Exception e) {
            log.warn("CodeOps evaluation case run failed. caseId={}", caseId, e);
            return Response.<CodeOpsEvalSummary>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @GetMapping("report")
    public Response<CodeOpsEvalReport> getLatestReport() {
        try {
            CodeOpsEvalReport report = codeOpsEvaluationService.getLastReport();
            if (report == null) {
                return Response.<CodeOpsEvalReport>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info("No report yet. Run an eval first.")
                        .build();
            }
            return Response.<CodeOpsEvalReport>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(report)
                    .build();
        } catch (Exception e) {
            return Response.<CodeOpsEvalReport>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @GetMapping("approval/{taskId}")
    public Response<Map<String, Object>> getApprovalStatus(@PathVariable String taskId) {
        HumanApprovalGate.ApprovalRecord record = humanApprovalGate.getStatus(taskId);
        if (record == null) {
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("No pending approval for this task")
                    .build();
        }
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(record.toMap())
                .build();
    }

    @PostMapping("approval/{taskId}/approve")
    public Response<Map<String, Object>> approveTask(@PathVariable String taskId) {
        try {
            HumanApprovalGate.ApprovalRecord record = engineeringTaskAgentService.approveTask(taskId);
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("Task approved by human reviewer")
                    .data(record.toMap())
                    .build();
        } catch (Exception e) {
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @PostMapping("approval/{taskId}/reject")
    public Response<Map<String, Object>> rejectTask(@PathVariable String taskId,
                                                     @RequestBody Map<String, String> body) {
        try {
            String reason = body.getOrDefault("reason", "No reason provided");
            HumanApprovalGate.ApprovalRecord record = engineeringTaskAgentService.rejectTask(taskId, reason);
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("Task rejected: " + reason)
                    .data(record.toMap())
                    .build();
        } catch (Exception e) {
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @GetMapping("scheduler/status")
    public Response<Map<String, Object>> getSchedulerStatus() {
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(incidentScheduler.getStatus())
                .build();
    }

    @PostMapping("scheduler/start")
    public Response<String> startScheduler() {
        incidentScheduler.start();
        return Response.<String>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("Scheduler started")
                .build();
    }

    @PostMapping("scheduler/stop")
    public Response<String> stopScheduler() {
        incidentScheduler.stop();
        return Response.<String>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("Scheduler stopped")
                .build();
    }

    @PostMapping("scheduler/simulate")
    public Response<Map<String, Object>> simulateAlertStorm(@RequestBody Map<String, Object> body) {
        int count = body.get("count") instanceof Number n ? n.intValue() : 100;
        String severity = body.get("severity") instanceof String s ? s : "HIGH";
        String runId = body.get("runId") instanceof String s && !s.isBlank()
                ? s : UUID.randomUUID().toString();
        int acceptedCount = 0;
        int dedupedCount = 0;

        // Simulate N alerts with slight variations to trigger both dedup and aggregation
        for (int i = 0; i < count; i++) {
            String fingerprint = runId + "-storm-test-" + (i % 5); // 5 unique fingerprints per simulation run
            String service = "order-service-" + (i % 3);  // 3 services
            var result = incidentScheduler.ingest(
                    fingerprint,
                    "StormSimulation" + (i % 5),
                    service,
                    severity,
                    "Simulated alert #" + i + " — " + severity + " on " + service,
                    "POST /api/orders/submit"
            );
            if (result != null) acceptedCount++;
            else dedupedCount++;
        }

        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(String.format("Sent %d alerts: %d deduped, %d accepted into queue",
                        count, dedupedCount, acceptedCount))
                .data(Map.of(
                        "runId", runId,
                        "totalSent", count,
                        "deduped", dedupedCount,
                        "accepted", acceptedCount,
                        "queueStats", incidentScheduler.getStatus()
                ))
                .build();
    }

    @GetMapping("model-router/stats")
    public Response<Map<String, Object>> getModelRouterStats() {
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(modelRouter.getStats())
                .build();
    }

}
