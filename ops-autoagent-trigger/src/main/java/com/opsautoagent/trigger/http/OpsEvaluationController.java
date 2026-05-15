package com.opsautoagent.trigger.http;

import com.opsautoagent.api.response.Response;
import com.opsautoagent.domain.ops.agent.eval.OpsEvaluationService;
import com.opsautoagent.domain.ops.agent.eval.OpsEvaluationSummary;
import com.opsautoagent.domain.ops.agent.eval.OpsMemoryToolchainEvaluationSummary;
import com.opsautoagent.domain.ops.agent.eval.OpsRunbookRagEvaluationService;
import com.opsautoagent.domain.ops.agent.eval.OpsRunbookRagEvaluationSummary;
import com.opsautoagent.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

@Slf4j
@RestController
@RequestMapping("/api/v1/ops/evaluation")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class OpsEvaluationController {

    @Resource
    private OpsEvaluationService opsEvaluationService;

    @Resource
    private OpsRunbookRagEvaluationService opsRunbookRagEvaluationService;

    @RequestMapping(value = "run", method = RequestMethod.POST)
    public Response<OpsEvaluationSummary> runEvaluation() {
        try {
            OpsEvaluationSummary summary = opsEvaluationService.runAllEnabledCases();
            return Response.<OpsEvaluationSummary>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(summary)
                    .build();
        } catch (Exception e) {
            log.warn("Ops evaluation run failed.", e);
            return Response.<OpsEvaluationSummary>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "run/{caseId}", method = RequestMethod.POST)
    public Response<OpsEvaluationSummary> runEvaluationCase(@PathVariable("caseId") String caseId) {
        try {
            OpsEvaluationSummary summary = opsEvaluationService.runCase(caseId);
            return Response.<OpsEvaluationSummary>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(summary)
                    .build();
        } catch (Exception e) {
            log.warn("Ops evaluation case run failed. caseId={}", caseId, e);
            return Response.<OpsEvaluationSummary>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "runbook-rag/run", method = RequestMethod.POST)
    public Response<OpsRunbookRagEvaluationSummary> runRunbookRagEvaluation() {
        try {
            OpsRunbookRagEvaluationSummary summary = opsRunbookRagEvaluationService.runRecallEvaluation();
            return Response.<OpsRunbookRagEvaluationSummary>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(summary)
                    .build();
        } catch (Exception e) {
            log.warn("Ops runbook RAG evaluation run failed.", e);
            return Response.<OpsRunbookRagEvaluationSummary>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "runbook-rag/ablation", method = RequestMethod.POST)
    public Response<OpsRunbookRagEvaluationSummary> runRunbookRagAblationEvaluation() {
        try {
            OpsRunbookRagEvaluationSummary summary = opsRunbookRagEvaluationService.runAblationEvaluation();
            return Response.<OpsRunbookRagEvaluationSummary>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(summary)
                    .build();
        } catch (Exception e) {
            log.warn("Ops runbook RAG ablation evaluation run failed.", e);
            return Response.<OpsRunbookRagEvaluationSummary>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "memory-toolchain/summary", method = RequestMethod.GET)
    public Response<OpsMemoryToolchainEvaluationSummary> memoryToolchainSummary() {
        try {
            OpsMemoryToolchainEvaluationSummary summary = opsEvaluationService.evaluateMemoryToolchain();
            return Response.<OpsMemoryToolchainEvaluationSummary>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(summary)
                    .build();
        } catch (Exception e) {
            log.warn("Ops memory/toolchain evaluation summary failed.", e);
            return Response.<OpsMemoryToolchainEvaluationSummary>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

}

