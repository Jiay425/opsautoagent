package com.opsautoagent.trigger.http;

import com.opsautoagent.api.response.Response;
import com.opsautoagent.domain.codeops.agent.eval.CodeOpsEvalReport;
import com.opsautoagent.domain.codeops.agent.eval.CodeOpsEvalSummary;
import com.opsautoagent.domain.codeops.agent.eval.CodeOpsEvaluationService;
import com.opsautoagent.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/codeops/evaluation")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class CodeOpsEvaluationController {

    @Resource
    private CodeOpsEvaluationService codeOpsEvaluationService;

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

}
