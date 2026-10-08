package com.novelforge.novel;

import com.novelforge.canon.CanonService;
import com.novelforge.conversation.ConversationService;
import com.novelforge.conversation.ConversationEventHub;
import com.novelforge.generation.ModelGateway;
import com.novelforge.generation.ReviewPolicy;
import com.novelforge.generation.StyleReviewPolicy;
import com.novelforge.generation.StateExtractionPolicy;
import com.novelforge.generation.ContinuityShadowService;
import com.novelforge.generation.PlotForeshadowShadowService;
import com.novelforge.generation.ShadowReviewEvaluationService;
import com.novelforge.generation.ProfessionalReviewReplayService;
import com.novelforge.generation.RetrievalContextService;
import com.novelforge.generation.StructuredMemoryContextShadowService;
import com.novelforge.generation.StructuredMemoryContinuityAbService;
import com.novelforge.generation.HistoricalStructuredMemoryShadowService;
import com.novelforge.generation.HistoricalStructuredMemoryContinuityAbService;
import com.novelforge.generation.HistoricalContinuityGrayService;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel.*;
import com.novelforge.task.TaskService;
import com.novelforge.outline.OutlinePipelineService;
import com.novelforge.outline.OutlineSpec;
import com.novelforge.projection.ProjectionOutbox;
import com.novelforge.projection.ElasticsearchShadowSearchService;
import com.novelforge.projection.RelationShadowEvaluationService;
import com.novelforge.projection.ImpactShadowEvaluationService;
import com.novelforge.workflow.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class NovelController {
    private final NovelRepository repository;
    private final WorkflowService workflow;
    private final WorkflowRules rules;
    private final WordCounter words;
    private final TaskService tasks;
    private final CanonService canon;
    private final ModelGateway model;
    private final ContinuityShadowService continuityShadows;
    private final PlotForeshadowShadowService plotForeshadowShadows;
    private final ShadowReviewEvaluationService shadowEvaluations;
    private final ProfessionalReviewReplayService professionalReplays;
    private final OutlinePipelineService outlinePipelines;
    private final ConversationService conversations;
    private final ConversationEventHub conversationEvents;
    private final String storageMode;
    private final ProjectionOutbox projectionOutbox;
    private final ElasticsearchShadowSearchService shadowSearch;
    private final RetrievalContextService retrievalContexts;
    private final RelationShadowEvaluationService relationEvaluations;
    private final ImpactShadowEvaluationService impactEvaluations;
    private final StructuredMemoryContextShadowService structuredMemoryContextShadows;
    private final StructuredMemoryContinuityAbService structuredMemoryContinuityAb;
    private final HistoricalStructuredMemoryShadowService historicalStructuredMemoryShadow;
    private final HistoricalStructuredMemoryContinuityAbService historicalStructuredMemoryContinuityAb;
    private final HistoricalContinuityGrayService historicalContinuityGray;
    public NovelController(NovelRepository repository, WorkflowService workflow, WorkflowRules rules, WordCounter words, TaskService tasks, CanonService canon, ModelGateway model,ContinuityShadowService continuityShadows,PlotForeshadowShadowService plotForeshadowShadows,ShadowReviewEvaluationService shadowEvaluations,ProfessionalReviewReplayService professionalReplays,OutlinePipelineService outlinePipelines,ConversationService conversations,ConversationEventHub conversationEvents,
                           @Value("${novelforge.storage.mode:legacy}") String storageMode,ProjectionOutbox projectionOutbox,
                           ElasticsearchShadowSearchService shadowSearch,RetrievalContextService retrievalContexts,
                           RelationShadowEvaluationService relationEvaluations,ImpactShadowEvaluationService impactEvaluations,
                           StructuredMemoryContextShadowService structuredMemoryContextShadows,
                           StructuredMemoryContinuityAbService structuredMemoryContinuityAb,
                           HistoricalStructuredMemoryShadowService historicalStructuredMemoryShadow,
                           HistoricalStructuredMemoryContinuityAbService historicalStructuredMemoryContinuityAb,
                           HistoricalContinuityGrayService historicalContinuityGray) {
        this.repository=repository; this.workflow=workflow; this.rules=rules; this.words=words; this.tasks=tasks; this.canon=canon; this.model=model;
        this.continuityShadows=continuityShadows;
        this.plotForeshadowShadows=plotForeshadowShadows;
        this.shadowEvaluations=shadowEvaluations;
        this.professionalReplays=professionalReplays;
        this.outlinePipelines=outlinePipelines;
        this.conversations=conversations;
        this.conversationEvents=conversationEvents;
        this.storageMode=storageMode;
        this.projectionOutbox=projectionOutbox;
        this.shadowSearch=shadowSearch;
        this.retrievalContexts=retrievalContexts;
        this.relationEvaluations=relationEvaluations;
        this.impactEvaluations=impactEvaluations;
        this.structuredMemoryContextShadows=structuredMemoryContextShadows;
        this.structuredMemoryContinuityAb=structuredMemoryContinuityAb;
        this.historicalStructuredMemoryShadow=historicalStructuredMemoryShadow;
        this.historicalStructuredMemoryContinuityAb=historicalStructuredMemoryContinuityAb;
        this.historicalContinuityGray=historicalContinuityGray;
    }
    public record Create(@NotBlank @Size(max=200) String title, @NotBlank @Size(max=20000) String synopsis,
                         @Min(10) @Max(50000000) Long targetWords, @Size(max=20000) String requirements) {}
    public record Generate(@NotNull Action action, String artifactId, @Size(max=20000) String instructions,
                           @NotBlank @Size(max=100) String requestKey, @Min(0) long revision) {}
    public record Confirm(@Min(0) long revision, @Size(max=2000) String overrideReason) {}
    public record Edit(@NotBlank String artifactId, @NotBlank String baseVersionId, @NotBlank @Size(max=300) String title,
                       @NotBlank @Size(max=250000) String content, @NotBlank @Size(max=5000) String summary,
                       @NotNull @Size(max=200) List<Fact> facts, Plan plan, @NotBlank @Size(max=2000) String reason, @Min(0) long revision) {}
    public record OutlineEdit(@NotBlank String baseVersionId,@NotBlank @Size(max=300) String title,
                              @NotBlank @Size(max=5000) String summary,@NotNull OutlineSpec outlineSpec,
                              @NotBlank @Size(max=2000) String reason,@Min(0) long revision) {}
    public record Budget(@Min(10) @Max(50000000) Long targetWords,
                         @Min(11) @Max(100000000) long approvedMaxWords,
                         @NotBlank @Size(max=2000) String reason, @Min(0) long revision) {}
    public record AutoStyle(boolean enabled,@Min(0) long revision) {}
    public record Retry(@NotBlank @Size(max=100) String requestKey, @Min(0) long revision) {}
    public record GuidedRevision(@NotBlank @Size(max=100) String requestKey, @Min(0) long revision) {}
    public record Finish(@NotBlank String checkId, @Min(0) long revision, boolean acknowledge) {}
    public record ShadowFeedback(@NotBlank String decision, @Size(max=1000) String note) {}
    public record ProfessionalReplay(@NotBlank @Size(max=100) String requestKey,@Size(max=40) String checker,
                                     @Min(1) @Max(20) Integer maxItems) {}
    public record ConversationStart(@Min(0) long revision,@Size(max=100) String threadId,Boolean newThread) {}
    public record ConversationMessageRequest(@NotBlank @Size(max=10000) String content,
                                             @NotBlank @Size(max=100) String requestKey) {}
    public record ConversationDecisionRequest(@NotNull DecisionStatus status) {}
    public record ProjectUpdateDecisionRequest(@NotNull ProjectUpdateStatus status) {}
    public record ConversationExecute(@NotBlank @Size(max=100) String requestKey,@Min(0) long revision) {}
    public record ProjectUpdatesApply(@Min(0) long revision) {}
    public record CandidateDismiss(@Min(0) long revision) {}
    public record ShadowSearch(@NotBlank @Size(max=2000) String query,@Min(1) Integer beforeChapter,
                               @Min(1) @Max(20) Integer limit) {}
    public record StructuredMemoryContinuityAb(@NotBlank @Size(max=100) String requestKey,
                                               @NotBlank @Size(max=300) String title,
                                               @NotBlank @Size(max=20000) String content,
                                               @Size(max=5000) String summary) {}
    public record HistoricalStructuredMemoryShadow(@NotBlank @Size(max=100) String requestKey,
                                                    @NotEmpty @Size(max=20) List<@Min(1) Integer> chapterNumbers) {}
    public record HistoricalStructuredMemoryContinuityAb(@NotBlank @Size(max=100) String requestKey,
                                                         @NotEmpty @Size(max=20) List<@Min(1) Integer> chapterNumbers,
                                                         @NotBlank @Size(max=300) String title,
                                                         @NotBlank @Size(max=20000) String content,
                                                         @Size(max=5000) String summary) {}

    @GetMapping("/health") public Object health() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("status","UP"); result.put("mode",model.mode()); result.put("modelReady",model.ready()); result.put("storageMode",storageMode);
        result.put("continuityShadowEnabled",continuityShadows.enabled()); result.put("plotForeshadowShadowEnabled",plotForeshadowShadows.enabled());
        result.put("neo4jProjection",projectionOutbox.status());
        result.put("elasticsearchProjection",projectionOutbox.elasticsearchStatus());
        result.put("retrievalContextEnabled",retrievalContexts.enabled());
        result.put("relationShadowEvaluationEnabled",relationEvaluations.enabled());
        result.put("impactShadowEvaluationEnabled",impactEvaluations.enabled());
        result.put("structuredMemoryContextShadowEnabled",structuredMemoryContextShadows.enabled());
        result.put("structuredMemoryContinuityAbEnabled",structuredMemoryContinuityAb.enabled());
        result.put("historicalStructuredMemoryShadowEnabled",historicalStructuredMemoryShadow.enabled());
        result.put("historicalStructuredMemoryContinuityAbEnabled",historicalStructuredMemoryContinuityAb.enabled());
        result.put("historicalContinuityGrayEnabled",historicalContinuityGray.enabled());
        result.put("historicalContinuityGrayNovelIds",historicalContinuityGray.novelIds());
        return result;
    }
    @GetMapping("/novels") public Object list() {
        return repository.list().stream().map(n -> Map.of("id", n.id, "title", n.title, "status", n.status, "words", words.approvedWords(n), "targetWords", n.targetWords)).toList();
    }
    @PostMapping("/novels") @ResponseStatus(HttpStatus.CREATED)
    public Object create(@Valid @RequestBody Create r) {
        return view(workflow.create(r.title(), r.synopsis(), r.targetWords()==null ? 100000 : r.targetWords(), r.requirements()));
    }
    @GetMapping("/novels/{id}") public Object get(@PathVariable String id) { return view(repository.get(id)); }
    @GetMapping("/novels/{id}/artifacts") public Object artifacts(@PathVariable String id) { return repository.get(id).artifacts; }
    @GetMapping("/novels/{id}/artifacts/{artifactId}/versions")
    public Object versions(@PathVariable String id, @PathVariable String artifactId) { return rules.artifact(repository.get(id), artifactId).versions; }
    @GetMapping("/novels/{id}/plan-batches") public Object plans(@PathVariable String id) { return repository.get(id).artifacts.stream().filter(a -> a.kind == Kind.PLAN).toList(); }
    @GetMapping("/novels/{id}/canon") public Object canon(@PathVariable String id) { return canon.at(repository.get(id), Integer.MAX_VALUE); }
    @GetMapping("/novels/{id}/structured-memory") public Object structuredMemory(@PathVariable String id) {
        return canon.structured(repository.get(id));
    }
    @GetMapping("/novels/{id}/shadow-relation-evaluation") public Object shadowRelationEvaluation(@PathVariable String id) {
        repository.get(id); return relationEvaluations.evaluate(id);
    }
    @GetMapping("/novels/{id}/shadow-impact-evaluation") public Object shadowImpactEvaluation(@PathVariable String id) {
        return impactEvaluations.evaluate(id);
    }
    @GetMapping("/novels/{id}/shadow-structured-memory-context") public Object shadowStructuredMemoryContext(@PathVariable String id) {
        return structuredMemoryContextShadows.evaluate(repository.get(id));
    }
    @PostMapping("/novels/{id}/shadow-structured-memory-continuity-ab") public Object shadowStructuredMemoryContinuityAb(
            @PathVariable String id,@Valid @RequestBody StructuredMemoryContinuityAb request) {
        return structuredMemoryContinuityAb.evaluate(repository.get(id),request.requestKey(),
                new StructuredMemoryContinuityAbService.Candidate(request.title(),request.content(),request.summary()));
    }
    @PostMapping("/novels/{id}/shadow-historical-structured-memory") public Object shadowHistoricalStructuredMemory(
            @PathVariable String id,@Valid @RequestBody HistoricalStructuredMemoryShadow request) {
        return historicalStructuredMemoryShadow.evaluate(repository.get(id),request.requestKey(),request.chapterNumbers());
    }
    @PostMapping("/novels/{id}/shadow-historical-structured-memory-continuity-ab")
    public Object shadowHistoricalStructuredMemoryContinuityAb(@PathVariable String id,
            @Valid @RequestBody HistoricalStructuredMemoryContinuityAb request) {
        return historicalStructuredMemoryContinuityAb.evaluate(repository.get(id),request.requestKey(),
                request.chapterNumbers(),new HistoricalStructuredMemoryContinuityAbService.Candidate(
                        request.title(),request.content(),request.summary()));
    }
    @PostMapping("/novels/{id}/shadow-retrievals") public Object shadowRetrieval(@PathVariable String id,
            @Valid @RequestBody ShadowSearch request) {
        return shadowSearch.search(id,request.query(),request.beforeChapter()==null?Integer.MAX_VALUE:request.beforeChapter(),
                request.limit()==null?5:request.limit());
    }
    @GetMapping("/novels/{id}/shadow-retrievals") public Object shadowRetrievals(@PathVariable String id) {
        repository.get(id); return shadowSearch.list(id);
    }
    @GetMapping("/novels/{id}/shadow-reviews") public Object shadowReviews(@PathVariable String id) {
        Novel novel=repository.get(id); return novel.shadowReviews==null?List.of():novel.shadowReviews;
    }
    @GetMapping("/novels/{id}/shadow-review-summary") public Object shadowReviewSummary(@PathVariable String id) {
        return shadowEvaluations.summary(repository.get(id));
    }
    @PostMapping("/novels/{id}/shadow-reviews/{reviewId}/feedback")
    public Object shadowFeedback(@PathVariable String id,@PathVariable String reviewId,
                                 @Valid @RequestBody ShadowFeedback r) {
        return view(shadowEvaluations.feedback(id,reviewId,r.decision(),r.note()));
    }
    @GetMapping("/novels/{id}/professional-review-replays")
    public Object professionalReplays(@PathVariable String id) { return professionalReplays.list(id); }
    @GetMapping("/novels/{id}/professional-review-replays/{batchId}")
    public Object professionalReplay(@PathVariable String id,@PathVariable String batchId) {
        return professionalReplays.find(id,batchId);
    }
    @PostMapping("/novels/{id}/professional-review-replays") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object professionalReplayStart(@PathVariable String id,@Valid @RequestBody ProfessionalReplay r) {
        return professionalReplays.start(id,r.requestKey(),r.checker(),r.maxItems()==null?20:r.maxItems());
    }
    @GetMapping("/novels/{id}/outline-pipelines")
    public Object outlinePipelines(@PathVariable String id) { return outlinePipelines.list(id); }
    @GetMapping("/novels/{id}/outline-pipelines/{workspaceId}")
    public Object outlinePipeline(@PathVariable String id,@PathVariable String workspaceId) {
        return outlinePipelines.find(id,workspaceId);
    }
    @GetMapping("/novels/{id}/conversations")
    public Object conversations(@PathVariable String id) { return conversations.list(id); }
    @GetMapping(value="/novels/{id}/conversations/{sessionId}/events",produces="text/event-stream")
    public SseEmitter conversationEvents(@PathVariable String id,@PathVariable String sessionId,
                                         @RequestHeader(value="Last-Event-ID",required=false) String lastEventId) {
        conversations.list(id).stream().filter(item->item.id.equals(sessionId)).findFirst()
                .orElseThrow(()->new com.novelforge.shared.Problem(404,"创作对话不存在"));
        return conversationEvents.subscribe(id,sessionId,lastEventId);
    }
    @PostMapping("/novels/{id}/conversations/outline") @ResponseStatus(HttpStatus.CREATED)
    public Object startOutlineConversation(@PathVariable String id,@Valid @RequestBody ConversationStart r) {
        return conversations.startOutline(id,r.revision(),r.threadId(),Boolean.TRUE.equals(r.newThread()));
    }
    @PostMapping("/novels/{id}/conversations/{sessionId}/messages")
    public Object conversationMessage(@PathVariable String id,@PathVariable String sessionId,
                                      @Valid @RequestBody ConversationMessageRequest r) {
        return conversations.send(id,sessionId,r.content(),r.requestKey());
    }
    @PostMapping("/novels/{id}/conversations/{sessionId}/turns/{turnId}/retry")
    public Object retryConversationTurn(@PathVariable String id,@PathVariable String sessionId,
                                        @PathVariable String turnId) {
        return conversations.retry(id,sessionId,turnId);
    }
    @PostMapping("/novels/{id}/conversations/{sessionId}/turns/{turnId}/cancel")
    public Object cancelConversationTurn(@PathVariable String id,@PathVariable String sessionId,
                                         @PathVariable String turnId) {
        return conversations.cancel(id,sessionId,turnId);
    }
    @PostMapping("/novels/{id}/conversations/{sessionId}/decisions/{decisionId}")
    public Object conversationDecision(@PathVariable String id,@PathVariable String sessionId,
                                       @PathVariable String decisionId,@Valid @RequestBody ConversationDecisionRequest r) {
        return conversations.decide(id,sessionId,decisionId,r.status());
    }
    @PostMapping("/novels/{id}/conversations/{sessionId}/project-updates/{updateId}")
    public Object projectUpdateDecision(@PathVariable String id,@PathVariable String sessionId,
                                        @PathVariable String updateId,@Valid @RequestBody ProjectUpdateDecisionRequest r) {
        return conversations.decideProjectUpdate(id,sessionId,updateId,r.status());
    }
    @PostMapping("/novels/{id}/conversations/{sessionId}/project-updates/apply")
    public Object applyProjectUpdates(@PathVariable String id,@PathVariable String sessionId,
                                      @Valid @RequestBody ProjectUpdatesApply r) {
        return conversations.applyProjectUpdates(id,sessionId,r.revision());
    }
    @PostMapping("/novels/{id}/conversations/{sessionId}/proposals/{proposalId}/execute")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object executeConversationProposal(@PathVariable String id,@PathVariable String sessionId,
                                              @PathVariable String proposalId,@Valid @RequestBody ConversationExecute r) {
        return conversations.execute(id,sessionId,proposalId,r.requestKey(),r.revision());
    }
    @PostMapping("/novels/{id}/generation-tasks") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object generate(@PathVariable String id, @Valid @RequestBody Generate r) { return tasks.submit(id, r.action(), r.artifactId(), r.instructions(), r.requestKey(), r.revision()); }
    @PostMapping("/novels/{id}/artifacts/{artifactId}/summary-tasks") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object summary(@PathVariable String id,@PathVariable String artifactId,@Valid @RequestBody Retry r) {
        return tasks.submitSummary(id,artifactId,r.requestKey(),r.revision());
    }
    @PostMapping("/novels/{id}/artifacts/{artifactId}/content-fix-tasks") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object contentFix(@PathVariable String id,@PathVariable String artifactId,@Valid @RequestBody GuidedRevision r) {
        return tasks.submitContentFix(id,artifactId,r.requestKey(),r.revision());
    }
    @PostMapping("/novels/{id}/artifacts/{artifactId}/style-polish-tasks") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object stylePolish(@PathVariable String id,@PathVariable String artifactId,@Valid @RequestBody GuidedRevision r) {
        return tasks.submitStylePolish(id,artifactId,r.requestKey(),r.revision());
    }
    @PostMapping("/novels/{id}/artifacts/{artifactId}/state-extraction-tasks") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object stateExtraction(@PathVariable String id,@PathVariable String artifactId,@Valid @RequestBody Retry r) {
        return tasks.submitStateExtraction(id,artifactId,r.requestKey(),r.revision());
    }
    @PostMapping("/novels/{id}/artifacts/{artifactId}/versions/{versionId}/confirm")
    public Object confirm(@PathVariable String id, @PathVariable String artifactId, @PathVariable String versionId, @Valid @RequestBody Confirm r) {
        return view(workflow.confirm(id, artifactId, versionId, r.revision(), r.overrideReason()));
    }
    @PostMapping("/novels/{id}/artifacts/{artifactId}/versions/{versionId}/dismiss")
    public Object dismissCandidate(@PathVariable String id,@PathVariable String artifactId,
                                   @PathVariable String versionId,@Valid @RequestBody CandidateDismiss r) {
        return view(workflow.dismissCandidate(id,artifactId,versionId,r.revision()));
    }
    @PostMapping("/novels/{id}/change-requests")
    public Object edit(@PathVariable String id, @Valid @RequestBody Edit r) {
        return view(workflow.edit(id, r.artifactId(), new WorkflowService.Edit(r.baseVersionId(), r.title(), r.content(), r.summary(), r.facts(), r.plan(), r.reason(), r.revision())));
    }
    @PostMapping("/novels/{id}/artifacts/{artifactId}/outline-versions")
    public Object editOutline(@PathVariable String id,@PathVariable String artifactId,
                              @Valid @RequestBody OutlineEdit r) {
        return view(workflow.editOutline(id,artifactId,new WorkflowService.OutlineEdit(r.baseVersionId(),r.title(),
                r.summary(),r.outlineSpec(),r.reason(),r.revision())));
    }
    @GetMapping("/novels/{id}/change-requests") public Object changes(@PathVariable String id) { return repository.get(id).changes; }
    @GetMapping("/novels/{id}/tasks/{taskId}") public Object task(@PathVariable String id, @PathVariable String taskId) { return tasks.find(repository.get(id), taskId); }
    @PostMapping("/novels/{id}/tasks/{taskId}/cancel") public Object cancel(@PathVariable String id, @PathVariable String taskId) { return tasks.cancel(id, taskId); }
    @PostMapping("/novels/{id}/tasks/{taskId}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object retry(@PathVariable String id, @PathVariable String taskId, @Valid @RequestBody Retry r) { return tasks.retry(id, taskId, r.requestKey(), r.revision()); }
    @PostMapping("/novels/{id}/budget-adjustments") public Object budget(@PathVariable String id, @Valid @RequestBody Budget r) {
        return view(workflow.budget(id, r.targetWords(), r.approvedMaxWords(), r.reason(), r.revision()));
    }
    @PostMapping("/novels/{id}/auto-style-settings") public Object autoStyle(@PathVariable String id,@Valid @RequestBody AutoStyle r) {
        return view(workflow.autoStyle(id,r.enabled(),r.revision()));
    }
    @PostMapping("/novels/{id}/completion-confirmations") public Object finish(@PathVariable String id, @Valid @RequestBody Finish r) { return view(workflow.finish(id, r.checkId(), r.revision(), r.acknowledge())); }

    private Object view(Novel n) {
        Artifact pending=rules.pending(n);
        return Map.of("novel", publicNovel(n), "nextAction", rules.nextAction(n), "wordCount", words.approvedWords(n),
                "pendingArtifactId", pending==null ? "" : pending.id, "canon", canon.at(n, Integer.MAX_VALUE),
                "mode", model.mode(), "reviewPolicyVersion", ReviewPolicy.VERSION,
                "styleReviewPolicyVersion", StyleReviewPolicy.VERSION,
                "stateExtractionPolicyVersion", StateExtractionPolicy.VERSION,
                "shadowReviewSummary",shadowEvaluations.summary(n));
    }
    private Object publicNovel(Novel n) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",n.id); result.put("ownerId",n.ownerId); result.put("title",n.title);
        result.put("synopsis",n.synopsis); result.put("requirements",n.requirements);
        result.put("targetWords",n.targetWords); result.put("approvedMaxWords",n.approvedMaxWords);
        result.put("autoStyleEnabled",Boolean.TRUE.equals(n.autoStyleEnabled));
        result.put("revision",n.revision); result.put("status",n.status); result.put("createdAt",n.createdAt);
        result.put("artifacts",n.artifacts); result.put("tasks",n.tasks); result.put("changes",n.changes);
        result.put("approvals",n.approvals); result.put("budgetChanges",n.budgetChanges);
        result.put("completionChecks",n.completionChecks);
        return result;
    }
}
