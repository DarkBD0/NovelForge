package com.novelforge.generation;

import java.util.List;

/** Evidence-cited audit used in addition to the general consistency review. */
public record ChapterEvidenceAudit(List<Check> planChecks, List<Check> claimChecks) {
    public record Check(String requirement, boolean satisfied, List<String> evidence) {}
}
