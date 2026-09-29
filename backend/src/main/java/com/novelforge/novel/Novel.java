package com.novelforge.novel;

import com.novelforge.outline.OutlineSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persisted aggregate: all updates are committed under a database row lock. */
public class Novel {
    public String id = uid();
    public String ownerId = "local-owner";
    public String title;
    public String synopsis;
    public String requirements = "";
    public long targetWords;
    public long approvedMaxWords;
    /** Null identifies novels saved before this preference existed; they remain opt-in. */
    public Boolean autoStyleEnabled;
    public long revision;
    public String status = "WRITING";
    public String createdAt = now();
    public List<Artifact> artifacts = new ArrayList<>();
    public List<Task> tasks = new ArrayList<>();
    public List<SourceSnapshot> sourceSnapshots = new ArrayList<>();
    public List<AgentRun> agentRuns = new ArrayList<>();
    public List<ShadowReview> shadowReviews = new ArrayList<>();
    /** Isolated A/B replays of historical professional reviews; never gates writing workflow. */
    public List<ProfessionalReviewReplayBatch> professionalReviewReplays = new ArrayList<>();
    /** One parent-task workspace for each outline multi-agent pipeline run. */
    public List<OutlinePipelineWorkspace> outlinePipelines = new ArrayList<>();
    public List<Change> changes = new ArrayList<>();
    public List<Approval> approvals = new ArrayList<>();
    public List<BudgetChange> budgetChanges = new ArrayList<>();
    public List<Completion> completionChecks = new ArrayList<>();

    public enum Kind { OUTLINE, CHARACTERS, PLAN, CHAPTER }
    public enum Action { OUTLINE, CHARACTERS, PLAN, CHAPTER, REWRITE, REVIEW, STYLE_REVIEW, COMPLETE }
    public enum TaskStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, STALE, INTERRUPTED }
    public enum AgentRunStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED, STALE, INTERRUPTED }
    public enum ShadowReviewStatus { RUNNING, SUCCEEDED, FAILED, INTERRUPTED }
    public enum ShadowReviewDecision { USEFUL, PARTLY_USEFUL, NOT_USEFUL }
    public enum ReplayItemStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, INTERRUPTED }
    public enum OutlinePipelineStatus { RUNNING, NEEDS_INPUT, SUCCEEDED, FAILED, CANCELLED, STALE, INTERRUPTED }

    public static class Artifact {
        public String id = uid();
        public Kind kind;
        public int chapterNumber;
        public int batchNumber;
        public String approvedVersionId;
        public boolean needsRevision;
        public List<Version> versions = new ArrayList<>();
        public Version latest() { return versions.isEmpty() ? null : versions.getLast(); }
        public Version approved() {
            return versions.stream().filter(v -> v.id.equals(approvedVersionId)).findFirst().orElse(null);
        }
        public boolean clean() {
            return approvedVersionId != null && !needsRevision && latest().id.equals(approvedVersionId);
        }
    }

    public static class Version {
        public String id = uid();
        public String baseVersionId;
        public String source;
        public String title;
        public String content;
        public String summary = "";
        public List<Fact> facts = new ArrayList<>();
        public Plan plan;
        /** Present only for outlines generated under the structured outline contract. */
        public OutlineSpec outlineSpec;
        public String outlineSpecHash;
        public Review review;
        public long reviewRevision = -1;
        public String reviewPolicyVersion;
        /** Advisory prose feedback only. It never participates in confirmation gating. */
        public Review styleReview;
        public String styleReviewPolicyVersion;
        /** Consecutive guided content-fix rounds. Reset by ordinary/manual revisions. */
        public int contentFixRound;
        /** A content candidate may receive at most one guided style-polish pass. */
        public int stylePolishRound;
        /** One bounded automatic repair is allowed for each author-triggered outline task. */
        public int outlineAutoRepairRound;
        public List<String> draftIssues = new ArrayList<>();
        public long basedOnRevision;
        public List<String> sourceVersionIds = new ArrayList<>();
        public String createdAt = now();
    }

    public static class Plan {
        public int startChapter;
        public int endChapter;
        public int prepareNextAfterChapter;
        public boolean finalBatch;
        public String triggerReason;
        public String handoff;
        public String assumptions;
        public List<ChapterBeat> chapters = new ArrayList<>();
    }
    /**
     * A chapter-level writing brief. The last four fields are advisory and may be
     * absent in plans written before the concise-prose workflow was introduced.
     */
    public record ChapterBeat(int number, String title, String purpose, Integer targetWords,
                              List<String> sceneBeats, String revealBoundary, String endingHook) {
        public ChapterBeat(int number, String title, String purpose) {
            this(number, title, purpose, null, List.of(), "", "");
        }
        public ChapterBeat {
            sceneBeats=sceneBeats==null?List.of():List.copyOf(sceneBeats);
            revealBoundary=revealBoundary==null?"":revealBoundary;
            endingHook=endingHook==null?"":endingHook;
        }
    }
    public record Fact(String key, String type, String detail, String state) {}
    public record ReviewIssue(String issueId, String location, String problem, String evidence, String suggestion, String severity) {
        public ReviewIssue(String location, String problem, String evidence, String suggestion, String severity) {
            this(null,location,problem,evidence,suggestion,severity);
        }
        public ReviewIssue {
            issueId=issueId==null||issueId.isBlank()?null:issueId;
            location=blank(location,"当前内容"); problem=blank(problem,"未说明具体问题");
            evidence=blank(evidence,"未提供依据"); suggestion=blank(suggestion,"请由作者核对后修改");
            severity=blank(severity,"必须修正");
        }
        public String text() { return "修改位置："+location+"；问题："+problem+"；依据："+evidence+"；建议："+suggestion; }
        private static String blank(String value,String fallback) { return value==null||value.isBlank()?fallback:value; }
    }
    public record Review(boolean passed, List<String> issues, boolean mainlineResolved,
                         boolean endingClear, boolean foreshadowingResolved, List<ReviewIssue> issueDetails) {
        public Review {
            issues=issues==null?List.of():List.copyOf(issues);
            issueDetails=issueDetails==null?issues.stream().map(i->new ReviewIssue("当前内容",i,"旧版检查报告未单独记录依据","按问题说明修改或由作者复核","必须修正")).toList():List.copyOf(issueDetails);
        }
        public Review(boolean passed,List<String> issues,boolean mainlineResolved,boolean endingClear,boolean foreshadowingResolved) {
            this(passed,issues,mainlineResolved,endingClear,foreshadowingResolved,null);
        }
        public Review withIssue(ReviewIssue detail) {
            var texts=new ArrayList<>(issues); texts.add(detail.text());
            var details=new ArrayList<>(issueDetails); details.add(detail);
            return new Review(false,texts,mainlineResolved,endingClear,foreshadowingResolved,details);
        }
    }
    public static class Task {
        public String id = uid();
        public String requestKey;
        public String requestFingerprint;
        public Action action;
        public String artifactId;
        public String instructions = "";
        public boolean summaryOnly;
        /** Empty for ordinary tasks; CONTENT_FIX or STYLE_POLISH for bounded guided revisions. */
        public String automationKind = "";
        public int automationRound;
        public long inputRevision;
        public String sourceSnapshotId;
        public List<String> sourceVersionIds = new ArrayList<>();
        public List<String> agentRunIds = new ArrayList<>();
        public TaskStatus status = TaskStatus.QUEUED;
        public String resultArtifactId;
        public String resultVersionId;
        public long stagedRevision = -1;
        public String error;
        public String createdAt = now();
        public String finishedAt;
    }
    public static class SourceSnapshot {
        public String id = uid();
        public String taskId;
        public long novelRevision;
        public Action action;
        public String artifactId;
        public String contextJson;
        public String contextHash;
        public List<String> sourceVersionIds = new ArrayList<>();
        public int chapterNumber;
        public int batchNumber;
        public String createdAt = now();
    }
    public static class AgentRun {
        public String id = uid();
        public String taskId;
        public String sourceSnapshotId;
        public String role;
        public String operation;
        public String inputVersionId;
        public List<String> upstreamAgentRunIds = new ArrayList<>();
        public AgentRunStatus status = AgentRunStatus.RUNNING;
        public String resultArtifactId;
        public String resultVersionId;
        public String error;
        public String startedAt = now();
        public String finishedAt;
    }
    /** Advisory world and character material. It is never an approved artifact or occurred fact. */
    public static class OutlineFoundationDraft {
        public String title;
        public String content;
        public String summary;
        public List<Fact> facts = new ArrayList<>();
    }
    /** Recoverable diagnostics for the single user-visible outline parent task. */
    public static class OutlinePipelineWorkspace {
        public String id = uid();
        public String taskId;
        public String sourceSnapshotId;
        public String resumedFromWorkspaceId;
        public OutlinePipelineStatus status = OutlinePipelineStatus.RUNNING;
        public String currentStep = "CREATED";
        public int maxModelCalls;
        public int modelCallsUsed;
        public String foundationRunId;
        public String architectRunId;
        public String continuityRunId;
        public String plotRunId;
        public OutlineFoundationDraft foundation;
        public String artifactId;
        public String versionId;
        public Review continuityReview;
        public Review plotReview;
        public Review deterministicReview;
        public Review combinedReview;
        /** Persisted impact matrix for a generated repair candidate. */
        public List<String> changedSections = new ArrayList<>();
        public boolean continuityReviewRequired = true;
        public boolean plotReviewRequired = true;
        public String continuityReviewReusedFromWorkspaceId;
        public String plotReviewReusedFromWorkspaceId;
        public String error;
        public String createdAt = now();
        public String finishedAt;
    }
    /** Experimental checker output. It is persisted for comparison but never gates confirmation. */
    public static class ShadowReview {
        public String id = uid();
        public String taskId;
        public String agentRunId;
        public String sourceSnapshotId;
        public String artifactId;
        public String versionId;
        public String checker;
        public String policyVersion;
        public ShadowReviewStatus status = ShadowReviewStatus.RUNNING;
        public Review review;
        public String error;
        public String createdAt = now();
        /** Set immediately before the professional checker calls the model. */
        public String modelStartedAt;
        public String finishedAt;
        /** Author evaluation of this experimental report; it never changes workflow state. */
        public ShadowReviewDecision authorDecision;
        public String authorNote = "";
        public String decidedAt;
    }
    /** One idempotent replay round comparing a frozen historical sample with a newer policy. */
    public static class ProfessionalReviewReplayBatch {
        public String id = uid();
        public String requestKey;
        public String policyVersion;
        /** Optional checker filter; null means the fixed sample across all professional checkers. */
        public String checker;
        public ShadowReviewStatus status = ShadowReviewStatus.RUNNING;
        public List<ProfessionalReviewReplayItem> items = new ArrayList<>();
        public String createdAt = now();
        public String finishedAt;
    }
    public static class ProfessionalReviewReplayItem {
        public String id = uid();
        public String baselineReviewId;
        public String checker;
        public String artifactId;
        public String versionId;
        public String sourceSnapshotId;
        public ReplayItemStatus status = ReplayItemStatus.QUEUED;
        /** Unfiltered model output retained only for controlled replay diagnosis. */
        public Review rawReview;
        public Review review;
        public String error;
        public String modelStartedAt;
        public String finishedAt;
    }
    public static class Change {
        public String id = uid();
        public String sourceArtifactId;
        public String reason;
        public List<String> affectedArtifactIds = new ArrayList<>();
        public String createdAt = now();
    }
    public record Approval(String artifactId, String versionId, long revision, String overrideReason, String time) {}
    /**
     * Audit entry for author-controlled word-count guidance.
     *
     * The target fields are nullable so documents written before target editing was
     * introduced remain readable; their older history only recorded max changes.
     */
    public record BudgetChange(Long previousTarget, Long newTarget,
                               long previousMax, long newMax, String reason, String time) {}
    public record Completion(String id, long revision, long wordCount, Review review, String time) {}
    public static String uid() { return UUID.randomUUID().toString(); }
    public static String now() { return Instant.now().toString(); }
}
