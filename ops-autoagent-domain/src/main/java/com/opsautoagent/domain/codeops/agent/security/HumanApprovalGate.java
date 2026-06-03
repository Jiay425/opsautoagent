package com.opsautoagent.domain.codeops.agent.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Human-in-the-loop approval gate.
 *
 * After the Agent generates a patch, compiles it, and runs tests,
 * it pauses here for human review before completing the fix.
 *
 * The approval gate stores:
 *   - Task summary: root cause, patch diff, test results
 *   - Risk assessment: rollback plan, observation metrics
 *   - Approval status: PENDING / APPROVED / REJECTED
 *
 * API: GET /api/v1/codeops/approval/{taskId} → get approval details
 *       POST /api/v1/codeops/approval/{taskId}/approve → approve
 *       POST /api/v1/codeops/approval/{taskId}/reject → reject with reason
 */
@Slf4j
@Service
public class HumanApprovalGate {

    private final ConcurrentMap<String, ApprovalRecord> pendingApprovals = new ConcurrentHashMap<>();

    /**
     * Submit a task for human approval.
     */
    public ApprovalRecord submitForApproval(String taskId, String caseName,
                                             String rootCause, String patchSummary,
                                             List<String> changedFiles, String riskLevel,
                                             String testResults,
                                             List<String> approvalReasons,
                                             Map<String, Object> evidenceSummary,
                                             Map<String, Object> patchQuality,
                                             Map<String, Object> patchSandbox) {
        ApprovalRecord record = ApprovalRecord.builder()
                .taskId(taskId)
                .caseName(caseName)
                .status("PENDING")
                .rootCause(rootCause)
                .patchSummary(patchSummary)
                .changedFiles(changedFiles)
                .riskLevel(riskLevel)
                .testResults(testResults)
                .approvalReasons(approvalReasons == null ? List.of() : approvalReasons)
                .evidenceSummary(evidenceSummary == null ? Map.of() : evidenceSummary)
                .patchQuality(patchQuality == null ? Map.of() : patchQuality)
                .patchSandbox(patchSandbox == null ? Map.of() : patchSandbox)
                .submittedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
        pendingApprovals.put(taskId, record);
        log.info("Task {} submitted for human approval. riskLevel={}, files={}",
                taskId, riskLevel, changedFiles);
        return record;
    }

    /**
     * Check if approval is required for this task (based on riskLevel or config).
     */
    public boolean isApprovalRequired(String riskLevel, boolean patchGenerated, boolean testsPassed) {
        if (patchGenerated && testsPassed) {
            return "HIGH".equalsIgnoreCase(riskLevel) || "CRITICAL".equalsIgnoreCase(riskLevel);
        }
        return false;
    }

    /**
     * Approve a pending task.
     */
    public ApprovalRecord approve(String taskId) {
        ApprovalRecord record = pendingApprovals.get(taskId);
        if (record == null) {
            throw new IllegalArgumentException("No pending approval for task: " + taskId);
        }
        record.setStatus("APPROVED");
        record.setApprovedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        log.info("Task {} APPROVED by human reviewer", taskId);
        return record;
    }

    /**
     * Reject a pending task with a reason.
     */
    public ApprovalRecord reject(String taskId, String reason) {
        ApprovalRecord record = pendingApprovals.get(taskId);
        if (record == null) {
            throw new IllegalArgumentException("No pending approval for task: " + taskId);
        }
        record.setStatus("REJECTED");
        record.setRejectionReason(reason);
        log.info("Task {} REJECTED. Reason: {}", taskId, reason);
        return record;
    }

    /**
     * Get approval status for a task.
     */
    public ApprovalRecord getStatus(String taskId) {
        return pendingApprovals.get(taskId);
    }

    public static class ApprovalRecord {
        private String taskId;
        private String caseName;
        private String status;         // PENDING | APPROVED | REJECTED
        private String rootCause;
        private String patchSummary;
        private List<String> changedFiles;
        private String riskLevel;
        private String testResults;
        private List<String> approvalReasons;
        private Map<String, Object> evidenceSummary;
        private Map<String, Object> patchQuality;
        private Map<String, Object> patchSandbox;
        private String submittedAt;
        private String approvedAt;
        private String rejectionReason;

        public static Builder builder() { return new Builder(); }

        public static class Builder {
            private final ApprovalRecord r = new ApprovalRecord();
            public Builder taskId(String v) { r.taskId = v; return this; }
            public Builder caseName(String v) { r.caseName = v; return this; }
            public Builder status(String v) { r.status = v; return this; }
            public Builder rootCause(String v) { r.rootCause = v; return this; }
            public Builder patchSummary(String v) { r.patchSummary = v; return this; }
            public Builder changedFiles(List<String> v) { r.changedFiles = v; return this; }
            public Builder riskLevel(String v) { r.riskLevel = v; return this; }
            public Builder testResults(String v) { r.testResults = v; return this; }
            public Builder approvalReasons(List<String> v) { r.approvalReasons = v; return this; }
            public Builder evidenceSummary(Map<String, Object> v) { r.evidenceSummary = v; return this; }
            public Builder patchQuality(Map<String, Object> v) { r.patchQuality = v; return this; }
            public Builder patchSandbox(Map<String, Object> v) { r.patchSandbox = v; return this; }
            public Builder submittedAt(String v) { r.submittedAt = v; return this; }
            public Builder approvedAt(String v) { r.approvedAt = v; return this; }
            public Builder rejectionReason(String v) { r.rejectionReason = v; return this; }
            public ApprovalRecord build() { return r; }
        }

        public String getTaskId() { return taskId; }
        public void setTaskId(String v) { taskId = v; }
        public String getCaseName() { return caseName; }
        public void setCaseName(String v) { caseName = v; }
        public String getStatus() { return status; }
        public void setStatus(String v) { status = v; }
        public String getRootCause() { return rootCause; }
        public void setRootCause(String v) { rootCause = v; }
        public String getPatchSummary() { return patchSummary; }
        public void setPatchSummary(String v) { patchSummary = v; }
        public List<String> getChangedFiles() { return changedFiles; }
        public void setChangedFiles(List<String> v) { changedFiles = v; }
        public String getRiskLevel() { return riskLevel; }
        public void setRiskLevel(String v) { riskLevel = v; }
        public String getTestResults() { return testResults; }
        public void setTestResults(String v) { testResults = v; }
        public List<String> getApprovalReasons() { return approvalReasons; }
        public void setApprovalReasons(List<String> v) { approvalReasons = v; }
        public Map<String, Object> getEvidenceSummary() { return evidenceSummary; }
        public void setEvidenceSummary(Map<String, Object> v) { evidenceSummary = v; }
        public Map<String, Object> getPatchQuality() { return patchQuality; }
        public void setPatchQuality(Map<String, Object> v) { patchQuality = v; }
        public Map<String, Object> getPatchSandbox() { return patchSandbox; }
        public void setPatchSandbox(Map<String, Object> v) { patchSandbox = v; }
        public String getSubmittedAt() { return submittedAt; }
        public void setSubmittedAt(String v) { submittedAt = v; }
        public String getApprovedAt() { return approvedAt; }
        public void setApprovedAt(String v) { approvedAt = v; }
        public String getRejectionReason() { return rejectionReason; }
        public void setRejectionReason(String v) { rejectionReason = v; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", taskId);
            m.put("caseName", caseName);
            m.put("status", status);
            m.put("rootCause", rootCause);
            m.put("patchSummary", patchSummary);
            m.put("changedFiles", changedFiles);
            m.put("riskLevel", riskLevel);
            m.put("testResults", testResults);
            m.put("approvalReasons", approvalReasons == null ? List.of() : approvalReasons);
            m.put("evidenceSummary", evidenceSummary == null ? Map.of() : evidenceSummary);
            m.put("patchQuality", patchQuality == null ? Map.of() : patchQuality);
            m.put("patchSandbox", patchSandbox == null ? Map.of() : patchSandbox);
            m.put("submittedAt", submittedAt);
            m.put("approvedAt", approvedAt);
            m.put("rejectionReason", rejectionReason);
            return m;
        }
    }
}
