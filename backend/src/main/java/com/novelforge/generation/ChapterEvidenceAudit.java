package com.novelforge.generation;

import java.util.List;

/** Evidence-cited audit used in addition to the general consistency review. */
public record ChapterEvidenceAudit(List<Check> planChecks, List<Check> claimChecks,
                                   List<NarrativeFinding> narrativeFindings) {
    public ChapterEvidenceAudit(List<Check> planChecks,List<Check> claimChecks) {
        this(planChecks,claimChecks,List.of());
    }
    public ChapterEvidenceAudit {
        narrativeFindings=narrativeFindings==null?List.of():List.copyOf(narrativeFindings);
    }
    public record Check(String requirement, boolean satisfied, List<String> evidence) {}
    public record NarrativeFinding(String problem,String firstQuote,String secondQuote,
                                   String suggestion,String severity) {}
}
