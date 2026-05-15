package com.opsautoagent.domain.ops.agent.evidence;

import com.opsautoagent.domain.ops.model.entity.EvidenceItemEntity;
import com.opsautoagent.domain.ops.model.entity.EvidenceSignalEntity;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.RootCauseCandidateEntity;
import com.opsautoagent.domain.ops.model.entity.RunbookMatchEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class OpsRunbookHypothesisGenerator {

    public List<RootCauseCandidateEntity> generate(IncidentCommandEntity command,
                                                   List<EvidenceSignalEntity> evidenceSignals,
                                                   List<RunbookMatchEntity> runbookMatches) {
        List<RootCauseCandidateEntity> hypotheses = new ArrayList<>();
        if (runbookMatches != null) {
            for (RunbookMatchEntity runbook : runbookMatches) {
                hypotheses.add(toHypothesis(command, evidenceSignals, runbook));
            }
        }
        if (hypotheses.isEmpty()) {
            hypotheses.add(unresolvedHypothesis(command, evidenceSignals));
        }
        return hypotheses.stream()
                .sorted(Comparator.comparing(RootCauseCandidateEntity::getConfidence).reversed())
                .toList();
    }

    private RootCauseCandidateEntity toHypothesis(IncidentCommandEntity command,
                                                  List<EvidenceSignalEntity> evidenceSignals,
                                                  RunbookMatchEntity runbook) {
        List<EvidenceSignalEntity> supportingSignals = supportingSignals(evidenceSignals, runbook);
        int score = supportScore(runbook, supportingSignals, evidenceSignals);
        List<String> missingEvidence = missingEvidence(supportingSignals);
        return RootCauseCandidateEntity.builder()
                .cause("Hypothesis from Runbook: " + blankToDefault(runbook.getTitle(), "unknown runbook pattern"))
                .category(blankToDefault(runbook.getCategory(), "runbook_pattern"))
                .confidence(score)
                .reasoning("Generated from retrieved Runbook pattern. The score is evidence-support strength, not a final root-cause probability. Evidence Reviewer must validate required and missing evidence before final report.")
                .evidences(toEvidenceItems(command, supportingSignals, runbook))
                .remediationSuggestions(List.of(
                        "Use the matched Runbook as remediation context only after Evidence Reviewer confirms supporting facts.",
                        "List missing evidence explicitly if required signals are absent.",
                        "Do not write this hypothesis as a final root cause without reviewer approval."))
                .hypothesis(true)
                .origin("RUNBOOK_PATTERN")
                .matchedRunbookId(runbook.getRunbookId())
                .missingEvidence(missingEvidence)
                .supportingSignals(supportingSignals.stream().map(EvidenceSignalEntity::getSignalId).toList())
                .build();
    }

    private RootCauseCandidateEntity unresolvedHypothesis(IncidentCommandEntity command,
                                                          List<EvidenceSignalEntity> evidenceSignals) {
        return RootCauseCandidateEntity.builder()
                .cause("Unresolved incident hypothesis")
                .category("insufficient_evidence")
                .confidence(0)
                .reasoning("No Runbook pattern was retrieved from the neutral evidence signals. Evidence Reviewer should request Runbook or missing telemetry before any final root-cause claim.")
                .evidences(toEvidenceItems(command, firstSignals(evidenceSignals, 5), null))
                .remediationSuggestions(List.of(
                        "Collect missing telemetry and retrieve Runbook patterns.",
                        "Keep final report in hypothesis form until at least one pattern is supported by evidence."))
                .hypothesis(true)
                .origin("UNRESOLVED_EVIDENCE")
                .missingEvidence(List.of("Runbook pattern evidence is unavailable or empty."))
                .supportingSignals(firstSignals(evidenceSignals, 5).stream().map(EvidenceSignalEntity::getSignalId).toList())
                .build();
    }

    private List<EvidenceSignalEntity> supportingSignals(List<EvidenceSignalEntity> evidenceSignals,
                                                         RunbookMatchEntity runbook) {
        if (evidenceSignals == null || evidenceSignals.isEmpty()) {
            return List.of();
        }
        String patternText = normalize(String.join(" ",
                value(runbook.getTitle()),
                value(runbook.getCategory()),
                value(runbook.getSummary()),
                value(runbook.getContent())));
        List<EvidenceSignalEntity> matched = new ArrayList<>();
        for (EvidenceSignalEntity signal : evidenceSignals) {
            String signalText = normalize(String.join(" ",
                    value(signal.getName()),
                    value(signal.getStatus()),
                    value(signal.getSummary()),
                    value(signal.getRawEvidence())));
            if (containsSharedToken(patternText, signalText)) {
                matched.add(signal);
            }
        }
        return matched;
    }

    private int supportScore(RunbookMatchEntity runbook,
                             List<EvidenceSignalEntity> supportingSignals,
                             List<EvidenceSignalEntity> allSignals) {
        int retrievalScore = runbook.getScore() == null ? 0 : Math.min(40, Math.max(0, runbook.getScore()) / 3);
        int supportCount = supportingSignals == null ? 0 : supportingSignals.size();
        int total = allSignals == null || allSignals.isEmpty() ? 1 : allSignals.size();
        int coverageScore = Math.min(35, (int) Math.round((supportCount * 35.0D) / total));
        int sourceScore = Math.min(25, distinctSources(supportingSignals).size() * 8);
        return Math.min(95, retrievalScore + coverageScore + sourceScore);
    }

    private List<String> missingEvidence(List<EvidenceSignalEntity> supportingSignals) {
        Set<String> sources = distinctSources(supportingSignals);
        List<String> missing = new ArrayList<>();
        if (!sources.contains("prometheus")) {
            missing.add("Prometheus metric evidence is not linked to this Runbook pattern.");
        }
        if (!sources.contains("elasticsearch")) {
            missing.add("Elasticsearch log evidence is not linked to this Runbook pattern.");
        }
        if (!sources.contains("skywalking")) {
            missing.add("SkyWalking trace evidence is not linked to this Runbook pattern.");
        }
        return missing;
    }

    private Set<String> distinctSources(List<EvidenceSignalEntity> signals) {
        Set<String> sources = new LinkedHashSet<>();
        if (signals == null) {
            return sources;
        }
        for (EvidenceSignalEntity signal : signals) {
            if (signal != null && !isBlank(signal.getSource())) {
                sources.add(signal.getSource());
            }
        }
        return sources;
    }

    private List<EvidenceItemEntity> toEvidenceItems(IncidentCommandEntity command,
                                                     List<EvidenceSignalEntity> signals,
                                                     RunbookMatchEntity runbook) {
        List<EvidenceItemEntity> items = new ArrayList<>();
        if (runbook != null) {
            items.add(EvidenceItemEntity.builder()
                    .source("runbook")
                    .category("pattern")
                    .title(blankToDefault(runbook.getTitle(), "Runbook pattern"))
                    .detail(blankToDefault(runbook.getSummary(), runbook.getContent()))
                    .confidence(runbook.getScore() == null ? 0 : runbook.getScore())
                    .build());
        }
        if (signals != null) {
            for (EvidenceSignalEntity signal : signals) {
                items.add(EvidenceItemEntity.builder()
                        .source(signal.getSource())
                        .category(signal.getEvidenceType())
                        .title(signal.getName() + " [" + signal.getStatus() + "]")
                        .detail(signal.getSummary())
                        .confidence("ANOMALY".equals(signal.getStatus()) ? 70 : 45)
                        .build());
            }
        }
        if (items.isEmpty()) {
            items.add(EvidenceItemEntity.builder()
                    .source("context")
                    .category("incident")
                    .title("Incident context")
                    .detail(command == null ? "" : command.getProblem())
                    .confidence(20)
                    .build());
        }
        return items;
    }

    private List<EvidenceSignalEntity> firstSignals(List<EvidenceSignalEntity> signals, int limit) {
        if (signals == null || signals.isEmpty()) {
            return List.of();
        }
        return signals.stream().limit(Math.max(1, limit)).toList();
    }

    private boolean containsSharedToken(String patternText, String signalText) {
        if (isBlank(patternText) || isBlank(signalText)) {
            return false;
        }
        for (String token : signalText.split("\\s+")) {
            if (token.length() >= 3 && patternText.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\p{IsHan}]+", " ");
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

