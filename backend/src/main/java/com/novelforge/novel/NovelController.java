package com.novelforge.novel;

import com.novelforge.canon.CanonService;
import com.novelforge.generation.ModelGateway;
import com.novelforge.generation.ReviewPolicy;
import com.novelforge.generation.StyleReviewPolicy;
import com.novelforge.generation.ContinuityShadowService;
import com.novelforge.generation.PlotForeshadowShadowService;
import com.novelforge.generation.ShadowReviewEvaluationService;
import com.novelforge.generation.ProfessionalReviewReplayService;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel.*;
import com.novelforge.task.TaskService;
import com.novelforge.outline.OutlinePipelineService;
import com.novelforge.outline.OutlineSpec;
import com.novelforge.workflow.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
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
    public NovelController(NovelRepository repository, WorkflowService workflow, WorkflowRules rules, WordCounter words, TaskService tasks, CanonService canon, ModelGateway model,ContinuityShadowService continuityShadows,PlotForeshadowShadowService plotForeshadowShadows,ShadowReviewEvaluationService shadowEvaluations,ProfessionalReviewReplayService professionalReplays,OutlinePipelineService outlinePipelines) {
        this.repository=repository; this.workflow=workflow; this.rules=rules; this.words=words; this.tasks=tasks; this.canon=canon; this.model=model;
        this.continuityShadows=continuityShadows;
        this.plotForeshadowShadows=plotForeshadowShadows;
        this.shadowEvaluations=shadowEvaluations;
        this.professionalReplays=professionalReplays;
        this.outlinePipelines=outlinePipelines;
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
    public record ProfessionalReplay(@NotBlank @Size(max=100) String requestKey,@Size(max=40) String checker) {}

    @GetMapping("/health") public Object health() { return Map.of("status","UP", "mode", model.mode(), "modelReady", model.ready(),
            "continuityShadowEnabled",continuityShadows.enabled(),
            "plotForeshadowShadowEnabled",plotForeshadowShadows.enabled()); }
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
        return professionalReplays.start(id,r.requestKey(),r.checker());
    }
    @GetMapping("/novels/{id}/outline-pipelines")
    public Object outlinePipelines(@PathVariable String id) { return outlinePipelines.list(id); }
    @GetMapping("/novels/{id}/outline-pipelines/{workspaceId}")
    public Object outlinePipeline(@PathVariable String id,@PathVariable String workspaceId) {
        return outlinePipelines.find(id,workspaceId);
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
    @PostMapping("/novels/{id}/artifacts/{artifactId}/versions/{versionId}/confirm")
    public Object confirm(@PathVariable String id, @PathVariable String artifactId, @PathVariable String versionId, @Valid @RequestBody Confirm r) {
        return view(workflow.confirm(id, artifactId, versionId, r.revision(), r.overrideReason()));
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
