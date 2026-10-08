package com.novelforge.task;

import com.novelforge.canon.CanonService;
import com.novelforge.generation.*;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.novel.WordCounter;
import com.novelforge.novel.ExplicitWordLimit;
import com.novelforge.revision.ImpactAnalyzer;
import com.novelforge.outline.OutlinePipelineService;
import com.novelforge.outline.OutlineStructureService;
import com.novelforge.outline.OutlineImpactAnalyzer;
import com.novelforge.shared.Problem;
import com.novelforge.workflow.*;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;
import static com.novelforge.shared.Problem.require;

@Service
public class TaskService {
    private static final Logger log=LoggerFactory.getLogger(TaskService.class);
    public static final String CONTENT_FIX="CONTENT_FIX";
    public static final String STYLE_POLISH="STYLE_POLISH";
    public static final String AUTO_STYLE_CHECK="AUTO_STYLE_CHECK";
    public static final String OUTLINE_AUTO_REPAIR="OUTLINE_AUTO_REPAIR";
    public static final String STATE_EXTRACTION_ONLY="STATE_EXTRACTION_ONLY";
    private final NovelRepository repository;
    private final WorkflowRules rules;
    private final WorkflowService workflow;
    private final ContextAssembler contexts;
    private final SourceSnapshotFactory snapshots;
    private final AgentOrchestrator agents;
    private final ImpactAnalyzer impact;
    private final WordCounter words;
    private final CanonService canon;
    private final ReviewAggregator reviews;
    private final ContinuityShadowService continuityShadows;
    private final HistoricalContinuityGrayService historicalContinuityGray;
    private final PlotForeshadowShadowService plotForeshadowShadows;
    private final DeterministicStyleEditor styleEditor;
    private final OutlinePipelineService outlinePipelines;
    private final OutlineStructureService outlineStructures;
    private final OutlineImpactAnalyzer outlineImpacts;
    private final StateExtractionPolicy stateExtractions;
    private final ContentReviewFingerprint contentReviewFingerprints;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "novel-task"); t.setDaemon(true); return t; });
    private final ConcurrentMap<String, FutureTask<Void>> running = new ConcurrentHashMap<>();
    private record StagedCandidate(String artifactId, String versionId, long revision, boolean needsReview) {}
    private record StateExtractionOutcome(String runId,List<Fact> facts,List<StateEntity> entities,
                                          List<StateRelation> relations,List<StateEvidence> evidence,String sourceHash) {}

    public TaskService(NovelRepository repository, WorkflowRules rules, WorkflowService workflow, ContextAssembler contexts,
                       SourceSnapshotFactory snapshots,
                       AgentOrchestrator agents, ImpactAnalyzer impact, WordCounter words, CanonService canon,
                       ReviewAggregator reviews,ContinuityShadowService continuityShadows,
                       HistoricalContinuityGrayService historicalContinuityGray,
                       PlotForeshadowShadowService plotForeshadowShadows,
                       DeterministicStyleEditor styleEditor,OutlinePipelineService outlinePipelines,
                       OutlineStructureService outlineStructures,OutlineImpactAnalyzer outlineImpacts,
                       StateExtractionPolicy stateExtractions,ContentReviewFingerprint contentReviewFingerprints) {
        this.repository=repository; this.rules=rules; this.workflow=workflow; this.contexts=contexts;
        this.snapshots=snapshots;
        this.agents=agents; this.impact=impact; this.words=words; this.canon=canon; this.reviews=reviews;
        this.continuityShadows=continuityShadows;
        this.historicalContinuityGray=historicalContinuityGray;
        this.plotForeshadowShadows=plotForeshadowShadows;
        this.styleEditor=styleEditor;
        this.outlinePipelines=outlinePipelines;
        this.outlineStructures=outlineStructures;
        this.outlineImpacts=outlineImpacts;
        this.stateExtractions=stateExtractions;
        this.contentReviewFingerprints=contentReviewFingerprints;
    }
    public Task submit(String id, Action action, String artifactId, String instructions, String key, long revision) {
        return submit(id,action,artifactId,instructions,key,revision,false);
    }
    public Task submitConversation(String id,Action action,String artifactId,ConversationBrief brief,
                                   String key,long revision) {
        require(brief!=null && brief.scope==ConversationScope.OUTLINE,"当前对话简报不能用于大纲任务");
        require(action==Action.OUTLINE || action==Action.REWRITE,"大纲对话只能生成或修订大纲");
        String instructions="只遵循上下文 conversationBrief 中由作者明确采纳的决定生成或修订大纲；"
                +"原始聊天、未采纳建议和开放问题都不是创作要求。";
        return submit(id,action,artifactId,instructions,key,revision,false,"",0,brief);
    }
    public Task submitSummary(String id,String artifactId,String key,long revision) {
        Novel novel=repository.get(id);
        Artifact artifact=rules.artifact(novel,artifactId);
        Version version=artifact.latest();
        require(version!=null && (version.summary==null || version.summary.isBlank()), "当前版本不需要补充摘要");
        String instructions="只补充当前内容摘要，不修改标题、正文、档案增量或章节规划。摘要必须准确概括现有内容，供后续连续创作使用。";
        return submit(id,Action.REWRITE,artifactId,instructions,key,revision,true);
    }
    public Task submitContentFix(String id,String artifactId,String key,long revision) {
        Novel novel=repository.get(id); Artifact artifact=rules.artifact(novel,artifactId); Version version=artifact.latest();
        require(version!=null && !artifact.clean(),"当前内容没有待处理的候选版本");
        require(rules.reviewCurrent(novel,version),"当前检查结果缺失或已过期，请先重新检查");
        List<ReviewIssue> required=version.review.issueDetails().stream()
                .filter(issue->"必须修正".equals(issue.severity())).toList();
        require(!required.isEmpty(),"当前检查没有必须修正的问题，不应启动内容自动修订");
        int round=version.contentFixRound+1;
        require(round<=2,"内容自动修订已达到两轮上限；请查看现有版本并由作者决定下一步");
        StringBuilder instructions=new StringBuilder("只对当前内容执行第 ").append(round)
                .append("/2 轮定点修订。逐条解决以下必须修正问题；优先使用精确局部替换，不得顺便改写无关段落、已确认依据或作者判断项。")
                .append("已确认的大纲、人物设定和章节规划高于当前候选及检查建议：如果建议给出多个分支，只能选择与已确认内容一致的分支；")
                .append("禁止选择需要修改已确认依据的分支，也不要把这种分支写进修订结果：\n");
        for (int i=0;i<required.size();i++) {
            ReviewIssue issue=required.get(i);
            instructions.append(i+1).append(". 修改位置：").append(issue.location())
                    .append("；问题：").append(issue.problem()).append("；依据：").append(issue.evidence())
                    .append("；建议：").append(issue.suggestion()).append('\n');
        }
        return submit(id,Action.REWRITE,artifactId,instructions.toString().strip(),key,revision,false,CONTENT_FIX,round);
    }
    public Task submitStylePolish(String id,String artifactId,String key,long revision) {
        Novel novel=repository.get(id); Artifact artifact=rules.artifact(novel,artifactId); Version version=artifact.latest();
        require(artifact.kind==Kind.CHAPTER && version!=null && !artifact.clean(),"安全文风修改只适用于待确认的章节正文");
        require(rules.reviewCurrent(novel,version) && version.review.passed(),"内容检查必须先通过，才能自动修改文风");
        require(version.styleReview!=null && StyleReviewPolicy.VERSION.equals(version.styleReviewPolicyVersion),
                "请先对当前版本执行文风检查");
        require(version.stylePolishRound<1,"当前内容已经自动优化过一次文风；剩余建议只展示，不再自动循环修改");
        require(version.styleReview.issueDetails()!=null && !version.styleReview.issueDetails().isEmpty(),
                "当前文风检查没有需要自动处理的问题");
        require(styleEditor.canApply(version),"当前文风建议没有可安全自动删除的重复解释；建议仍会保留，请由作者决定是否修改");
        StringBuilder instructions=new StringBuilder(StylePatchGuard.INSTRUCTION_MARKER)
                .append("只对当前章节执行唯一一轮确定性文风删减。系统只会删除报告中逐字存在、唯一出现且明确属于重复解释的原句；其他建议保留给作者决定：\n");
        List<ReviewIssue> issues=version.styleReview.issueDetails();
        for (int i=0;i<issues.size();i++) {
            ReviewIssue issue=issues.get(i);
            instructions.append(i+1).append(". 位置：").append(issue.location())
                    .append("；原文依据：").append(issue.evidence())
                    .append("；建议：").append(issue.suggestion()).append('\n');
        }
        return submit(id,Action.REWRITE,artifactId,instructions.toString().strip(),key,revision,false,STYLE_POLISH,1);
    }
    public Task submitStateExtraction(String id,String artifactId,String key,long revision) {
        Novel novel=repository.get(id); Artifact artifact=rules.artifact(novel,artifactId); Version version=artifact.latest();
        require(artifact.kind==Kind.CHAPTER && version!=null && !artifact.clean(),
                "状态提取只适用于待确认的章节正文");
        require(version.stateExtractionRequired && !stateExtractions.current(version),
                "当前章节不需要重新提取状态");
        require(rules.reviewCurrent(novel,version) && version.review.passed(),
                "内容检查必须先通过，才能只重试状态提取");
        return submit(id,Action.REVIEW,artifactId,
                "只重试独立状态提取；复用当前已经通过的内容检查，不修改正文、摘要或检查结论。",
                key,revision,false,STATE_EXTRACTION_ONLY,1);
    }
    private Task submit(String id, Action action, String artifactId, String instructions, String key, long revision,
                        boolean summaryOnly) {
        return submit(id,action,artifactId,instructions,key,revision,summaryOnly,"",0);
    }
    private Task submit(String id, Action action, String artifactId, String instructions, String key, long revision,
                        boolean summaryOnly,String automationKind,int automationRound) {
        return submit(id,action,artifactId,instructions,key,revision,summaryOnly,automationKind,automationRound,null);
    }
    private Task submit(String id, Action action, String artifactId, String instructions, String key, long revision,
                        boolean summaryOnly,String automationKind,int automationRound,ConversationBrief conversationBrief) {
        require(key != null && !key.isBlank() && key.length() <= 100, "请求需要不超过100字符的幂等键");
        String fingerprint = action + "|" + artifactId + "|" + instructions + "|" + revision + "|summaryOnly=" + summaryOnly
                + "|automation="+automationKind+":"+automationRound+"|conversationBrief="
                +(conversationBrief==null?"":conversationBrief.id+":"+conversationBrief.hash);
        Task task = repository.update(id, n -> {
            Task existing = n.tasks.stream().filter(t -> key.equals(t.requestKey)).findFirst().orElse(null);
            if (existing != null) { require(fingerprint.equals(existing.requestFingerprint), "同一幂等键不能用于不同请求"); return existing; }
            workflow.expected(n, revision); workflow.idle(n);
            if (STATE_EXTRACTION_ONLY.equals(automationKind)) {
                Artifact chapter=rules.artifact(n,artifactId); Version version=chapter.latest();
                require(action==Action.REVIEW && chapter.kind==Kind.CHAPTER && version!=null && !chapter.clean(),
                        "状态提取只适用于待确认的章节正文");
                require(version.stateExtractionRequired && !stateExtractions.current(version),
                        "当前章节不需要重新提取状态");
                require(rules.reviewCurrent(n,version) && version.review.passed(),
                        "内容检查必须先通过，才能只重试状态提取");
            } else rules.validateAction(n, action, artifactId);
            require(agents.ready(), "真实模型尚未配置地址和名称");
            ConversationBrief persistedBrief=null;
            if (conversationBrief!=null) {
                persistedBrief=n.conversationBriefs.stream().filter(item->item.id.equals(conversationBrief.id)).findFirst().orElse(null);
                require(persistedBrief!=null && Objects.equals(persistedBrief.hash,conversationBrief.hash),
                        "对话简报不存在或校验失败");
                require(persistedBrief.baseRevision==n.revision,"对话简报所依据的小说版本已经变化，请重新整理");
            }
            var context = contexts.assemble(n, action, artifactId,persistedBrief);
            Task t = new Task(); t.action=action; t.artifactId=artifactId; t.instructions=instructions == null ? "" : instructions;
            t.summaryOnly=summaryOnly;
            t.automationKind=automationKind==null?"":automationKind; t.automationRound=automationRound;
            t.inputRevision=n.revision; t.requestKey=key; t.requestFingerprint=fingerprint;
            SourceSnapshot source=snapshots.capture(t.id,n.revision,action,artifactId,context);
            if (persistedBrief!=null) {
                t.conversationBriefId=persistedBrief.id; t.conversationBriefHash=persistedBrief.hash;
                t.acceptedDecisionIds=new ArrayList<>(persistedBrief.acceptedDecisionIds);
                source.conversationBriefId=persistedBrief.id; source.conversationBriefHash=persistedBrief.hash;
                source.acceptedDecisionIds=new ArrayList<>(persistedBrief.acceptedDecisionIds);
            }
            t.sourceSnapshotId=source.id; t.sourceVersionIds=new ArrayList<>(source.sourceVersionIds);
            n.sourceSnapshots.add(source); n.tasks.add(t); return t;
        });
        if (task.status == TaskStatus.QUEUED) launch(id, task.id);
        return task;
    }
    private void launch(String novelId, String taskId) {
        FutureTask<Void> future = new FutureTask<>(() -> { try { execute(novelId, taskId); } finally { running.remove(taskId); } return null; });
        if (running.putIfAbsent(taskId, future) == null) executor.execute(future);
    }
    private void execute(String id, String taskId) {
        String activeAgentRunId=null;
        String activeOutlineWorkspaceId=null;
        try {
            boolean started = repository.update(id, n -> {
                Task t = find(n, taskId);
                if (t.status != TaskStatus.QUEUED) return false;
                if (t.inputRevision != n.revision) { t.status=TaskStatus.STALE; t.progressStage="STALE"; t.finishedAt=Novel.now(); return false; }
                t.status=TaskStatus.RUNNING; t.progressStage="PREPARING"; return true;
            });
            if (!started) return;
            Novel snapshot = repository.get(id);
            Task task = find(snapshot, taskId);
            Artifact target = task.artifactId == null ? null : rules.artifact(snapshot, task.artifactId);
            SourceSnapshot source=sourceSnapshot(snapshot,task.sourceSnapshotId);
            require(source.taskId.equals(task.id) && source.novelRevision==task.inputRevision,
                    "任务来源快照与任务版本不一致，已停止调用模型");
            var context = snapshots.restore(source);
            var request = new ModelGateway.Request(task.action, snapshot, target, context, task.instructions);
            ModelGateway.Generated candidate = null;
            StagedCandidate staged=null;
            String generationRunId=null;
            String foundationRunId=null;
            OutlinePipelineWorkspace previousOutlineWorkspace=null;
            OutlineImpactAnalyzer.ReviewScope outlineReviewScope=null;
            boolean deferredStylePolish=STYLE_POLISH.equals(task.automationKind);
            boolean stateExtractionOnly=STATE_EXTRACTION_ONLY.equals(task.automationKind);
            boolean outlineTarget=task.action==Action.OUTLINE || (target!=null && target.kind==Kind.OUTLINE);
            if (outlineTarget) {
                String resumedFrom=null;
                Version scopeBase=null;
                if (target!=null && target.latest()!=null) {
                    scopeBase=target.latest();
                    if (task.action==Action.REVIEW && scopeBase.baseVersionId!=null) {
                        String baseVersionId=scopeBase.baseVersionId;
                        scopeBase=target.versions.stream().filter(version->baseVersionId.equals(version.id))
                                .findFirst().orElse(scopeBase);
                    }
                    previousOutlineWorkspace=outlinePipelines.latestForVersion(snapshot,scopeBase.id);
                    resumedFrom=previousOutlineWorkspace==null?null:previousOutlineWorkspace.id;
                }
                int callLimit=task.action==Action.OUTLINE?4:task.action==Action.REWRITE?3:2;
                activeOutlineWorkspaceId=outlinePipelines.begin(id,taskId,source.id,callLimit,
                        target==null?null:target.id,target==null||target.latest()==null?null:target.latest().id,resumedFrom);
                if (task.action==Action.REVIEW && target!=null && target.latest()!=null && scopeBase!=null) {
                    outlineReviewScope=outlineImpacts.analyze(scopeBase.outlineSpec,target.latest().outlineSpec);
                    outlinePipelines.saveReviewScope(id,activeOutlineWorkspaceId,outlineReviewScope);
                }
            }
            if (task.action==Action.OUTLINE) {
                progress(id,taskId,"DESIGNING");
                ModelGateway.Request foundationRequest=agents.contextFor(request,AgentRole.CHARACTER_WORLD_DESIGNER);
                foundationRunId=startAgentRun(id,taskId,source.id,AgentRole.CHARACTER_WORLD_DESIGNER,
                        "outline-foundation",null,List.of(),foundationRequest.context());
                activeAgentRunId=foundationRunId;
                outlinePipelines.startStep(id,activeOutlineWorkspaceId,OutlinePipelineService.FOUNDATION,foundationRunId);
                ModelGateway.Generated foundationGenerated=agents.outlineFoundation(foundationRequest);
                OutlineFoundationDraft foundation=outlinePipelines.saveFoundation(id,activeOutlineWorkspaceId,foundationGenerated);
                completeAgentRun(id,foundationRunId,null,null);
                activeAgentRunId=null;
                request=outlinePipelines.withFoundation(request,foundation);
            }
            if (task.action == Action.REVIEW || task.action == Action.STYLE_REVIEW) {
                Version v = target.latest(); candidate = new ModelGateway.Generated(v.title, v.content, v.summary,
                        v.facts, v.plan, v.outlineSpec, List.of());
            } else if (task.action != Action.COMPLETE) {
                progress(id,taskId,"GENERATING");
                AgentRole generationRole=deferredStylePolish
                        ? AgentRole.STYLE_EDITOR : outlineTarget ? AgentRole.STORY_ARCHITECT : agents.generationRole(task.action);
                ModelGateway.Request generationRequest=agents.contextFor(request,generationRole);
                generationRunId=startAgentRun(id,taskId,source.id,generationRole,
                        deferredStylePolish?"deterministic-style-edit":outlineTarget&&task.action==Action.REWRITE
                                ?"outline-rewrite":"generate",
                        target==null||target.latest()==null?null:target.latest().id,
                        foundationRunId==null?List.of():List.of(foundationRunId),generationRequest.context());
                activeAgentRunId=generationRunId;
                if (outlineTarget)
                    outlinePipelines.startStep(id,activeOutlineWorkspaceId,OutlinePipelineService.ARCHITECT,generationRunId);
                candidate = deferredStylePolish
                        ? styleEditor.apply(target.latest()) : agents.generate(generationRequest);
                if (outlineTarget && !deferredStylePolish)
                    candidate=outlineStructures.normalizeGenerated(snapshot,candidate);
                if (!task.summaryOnly && (task.action==Action.CHAPTER || target!=null && target.kind==Kind.CHAPTER))
                    candidate=withoutWriterFacts(candidate);
                if (outlineTarget && OUTLINE_AUTO_REPAIR.equals(task.automationKind)) {
                    outlineReviewScope=outlineImpacts.analyze(target==null||target.latest()==null?null:target.latest().outlineSpec,
                            candidate.outlineSpec());
                    outlinePipelines.saveReviewScope(id,activeOutlineWorkspaceId,outlineReviewScope);
                }
                if (task.summaryOnly) validateSummaryOnly(target,candidate);
                if (!deferredStylePolish) {
                    staged=stageCandidate(id,taskId,task,context,candidate,generationRunId,activeOutlineWorkspaceId);
                    activeAgentRunId=null;
                    if (staged==null) {
                        stopOutlineWorkspaceForTask(id,taskId,activeOutlineWorkspaceId);
                        return;
                    }
                    if (!staged.needsReview()) {
                        return;
                    }
                }
            }
            // Cancel between calls, not only after a potentially expensive model request.
            if (find(repository.get(id), taskId).status != TaskStatus.RUNNING) {
                stopOutlineWorkspaceForTask(id,taskId,activeOutlineWorkspaceId);
                return;
            }
            Review deterministicOutlineReview=null;
            if (outlineTarget && candidate!=null && candidate.outlineSpec()!=null) {
                progress(id,taskId,"CHECKING_STRUCTURE");
                Version stored=staged!=null
                        ? rules.artifact(repository.get(id),staged.artifactId()).latest()
                        : target==null?null:target.latest();
                deterministicOutlineReview=outlineStructures.validate(snapshot,candidate,stored);
                outlinePipelines.saveDeterministicReview(id,activeOutlineWorkspaceId,deterministicOutlineReview);
                if (!deterministicOutlineReview.passed()) {
                    finishOutlineWithDeterministicIssues(id,taskId,staged,target,deterministicOutlineReview,activeOutlineWorkspaceId);
                    queueAutomaticOutlineRepair(id,staged==null?target.id:staged.artifactId(),
                            staged==null?target.latest().id:staged.versionId(),deterministicOutlineReview,task);
                    return;
                }
            }
            String inputVersionId=staged!=null?staged.versionId()
                    :target==null||target.latest()==null?null:target.latest().id;
            String reviewRunId;
            Review review;
            String contentReviewFingerprint=null;
            if (outlineTarget) {
                List<String> auditUpstream=generationRunId==null?List.of():List.of(generationRunId);
                String continuityRunId=null;
                Review continuity;
                boolean runContinuity=outlineReviewScope==null || outlineReviewScope.continuityRequired()
                        || previousOutlineWorkspace==null || previousOutlineWorkspace.continuityReview==null;
                if (runContinuity) {
                    progress(id,taskId,"CHECKING_CONTINUITY");
                    ModelGateway.Request continuityRequest=agents.contextFor(request,AgentRole.CONTINUITY_AUDITOR);
                    continuityRunId=startAgentRun(id,taskId,source.id,AgentRole.CONTINUITY_AUDITOR,
                            "outline-continuity-review",inputVersionId,auditUpstream,continuityRequest.context());
                    activeAgentRunId=continuityRunId;
                    outlinePipelines.startStep(id,activeOutlineWorkspaceId,OutlinePipelineService.CONTINUITY,continuityRunId);
                    continuity=agents.outlineContinuityReview(continuityRequest,candidate);
                    outlinePipelines.saveAudit(id,activeOutlineWorkspaceId,OutlinePipelineService.CONTINUITY,continuity);
                    completeAgentRun(id,continuityRunId,staged==null?target.id:staged.artifactId(),inputVersionId);
                    activeAgentRunId=null;
                } else {
                    continuity=previousOutlineWorkspace.continuityReview;
                    outlinePipelines.reuseAudit(id,activeOutlineWorkspaceId,OutlinePipelineService.CONTINUITY,
                            continuity,previousOutlineWorkspace.id);
                }

                String plotRunId=null;
                Review plot;
                boolean runPlot=outlineReviewScope==null || outlineReviewScope.plotRequired()
                        || previousOutlineWorkspace==null || previousOutlineWorkspace.plotReview==null;
                if (runPlot) {
                    progress(id,taskId,"CHECKING_PLOT");
                    ModelGateway.Request plotRequest=agents.contextFor(request,AgentRole.PLOT_FORESHADOW_AUDITOR);
                    plotRunId=startAgentRun(id,taskId,source.id,AgentRole.PLOT_FORESHADOW_AUDITOR,
                            "outline-plot-review",inputVersionId,auditUpstream,plotRequest.context());
                    activeAgentRunId=plotRunId;
                    outlinePipelines.startStep(id,activeOutlineWorkspaceId,OutlinePipelineService.PLOT,plotRunId);
                    plot=agents.outlinePlotReview(plotRequest,candidate);
                    outlinePipelines.saveAudit(id,activeOutlineWorkspaceId,OutlinePipelineService.PLOT,plot);
                    completeAgentRun(id,plotRunId,staged==null?target.id:staged.artifactId(),inputVersionId);
                    activeAgentRunId=null;
                } else {
                    plot=previousOutlineWorkspace.plotReview;
                    outlinePipelines.reuseAudit(id,activeOutlineWorkspaceId,OutlinePipelineService.PLOT,
                            plot,previousOutlineWorkspace.id);
                }
                review=outlinePipelines.merge(deterministicOutlineReview,continuity,plot);
                reviewRunId=plotRunId!=null?plotRunId:continuityRunId!=null?continuityRunId:generationRunId;
            } else if (stateExtractionOnly) {
                require(target!=null && target.kind==Kind.CHAPTER && target.latest()!=null,
                        "只重试状态提取的章节不存在");
                require(rules.reviewCurrent(snapshot,target.latest()) && target.latest().review.passed(),
                        "当前内容检查已经变化，请先重新检查");
                review=target.latest().review;
                reviewRunId=null;
                contentReviewFingerprint=target.latest().contentReviewFingerprint;
            } else {
                boolean fingerprintedContent=candidate!=null
                        && task.action!=Action.STYLE_REVIEW && task.action!=Action.COMPLETE;
                if(fingerprintedContent)
                    contentReviewFingerprint=contentReviewFingerprints.of(snapshot,target,candidate,task.instructions);
                Review reusable=fingerprintedContent?reusableContentReview(target,contentReviewFingerprint):null;
                if(reusable!=null) {
                    progress(id,taskId,"REUSING_CONTENT_REVIEW");
                    reviewRunId=null;
                    review=reusable;
                } else {
                    progress(id,taskId,"CHECKING");
                    AgentRole reviewRole=agents.reviewRole(task.action);
                    ModelGateway.Request reviewRequest=agents.contextFor(request,reviewRole);
                    reviewRunId=startAgentRun(id,taskId,source.id,reviewRole,"review",
                            inputVersionId,generationRunId==null?List.of():List.of(generationRunId),reviewRequest.context());
                    activeAgentRunId=reviewRunId;
                    review=agents.review(reviewRequest,candidate);
                }
            }
            Version previous=task.action==Action.REWRITE && target!=null ? target.latest() : null;
            if (!stateExtractionOnly)
                review=task.action==Action.STYLE_REVIEW ? reviews.aggregateAdvisory(review)
                        : reviews.aggregate(snapshot,target,task.action,task.instructions,candidate,previous,review,
                        CONTENT_FIX.equals(task.automationKind)?task.automationRound:0);
            Review finalReview=review;
            String finalReviewRunId=reviewRunId;
            String finalContentReviewFingerprint=contentReviewFingerprint;
            Review styleRecheck=null;
            String styleRecheckRunId=null;
            if (deferredStylePolish) {
                require(finalReview.passed(),
                        "自动文风修改未通过内容一致性检查，未创建候选版本；原内容保持不变，相关建议保留给作者决定");
                var styleRequest=new ModelGateway.Request(Action.STYLE_REVIEW,snapshot,target,context,
                        "自动文风修改后的唯一一次复查；剩余建议只展示，不再自动修改。");
                styleRequest=agents.contextFor(styleRequest,AgentRole.STYLE_AUDITOR);
                styleRecheckRunId=startAgentRun(id,taskId,source.id,AgentRole.STYLE_AUDITOR,"style-recheck",
                        inputVersionId,List.of(finalReviewRunId),styleRequest.context());
                activeAgentRunId=styleRecheckRunId;
                styleRecheck=reviews.aggregateAdvisory(agents.review(styleRequest,candidate));
                // Do not make the polished text the latest candidate until both its content
                // safety check and its one permitted style recheck have completed.
                staged=stageCandidate(id,taskId,task,context,candidate,generationRunId,activeOutlineWorkspaceId);
                if (staged==null || !staged.needsReview()) return;
                inputVersionId=staged.versionId();
            }
            StateExtractionOutcome stateOutcome=null;
            Artifact stateArtifact=staged!=null
                    ?rules.artifact(repository.get(id),staged.artifactId()):target;
            Version stateVersion=stateArtifact==null?null:stateArtifact.latest();
            if (finalReview.passed() && stateArtifact!=null && stateArtifact.kind==Kind.CHAPTER
                    && task.action!=Action.STYLE_REVIEW && task.action!=Action.COMPLETE
                    && !stateExtractions.current(stateVersion)) {
                progress(id,taskId,"EXTRACTING_STATE");
                ModelGateway.Generated stateCandidate=new ModelGateway.Generated(stateVersion.title,stateVersion.content,
                        stateVersion.summary,List.of(),stateVersion.plan,stateVersion.outlineSpec,List.of());
                ModelGateway.Request stateRequest=new ModelGateway.Request(task.action,snapshot,stateArtifact,context,
                        "只从已经通过内容检查的当前章节正文提取后续创作需要的状态变化，不修改正文。");
                stateRequest=agents.contextFor(stateRequest,AgentRole.STATE_EXTRACTOR);
                String stateRunId=startAgentRun(id,taskId,source.id,AgentRole.STATE_EXTRACTOR,"extract-state",
                        stateVersion.id,finalReviewRunId==null?List.of():List.of(finalReviewRunId),stateRequest.context());
                activeAgentRunId=stateRunId;
                markStateExtractionRunning(id,stateArtifact.id,stateVersion.id,stateRunId);
                try {
                    ModelGateway.StateExtraction extracted=agents.extractState(stateRequest,stateCandidate);
                    StateExtractionPolicy.ValidatedState validated=stateExtractions.validateAll(stateCandidate,extracted);
                    stateOutcome=new StateExtractionOutcome(stateRunId,validated.facts(),validated.entities(),
                            validated.relations(),validated.evidence(),
                            stateExtractions.contentHash(stateVersion.content));
                } catch(Exception failure) {
                    preserveReviewAndMarkStateExtractionFailed(id,taskId,stateArtifact.id,stateVersion.id,
                            finalReview,finalReviewRunId,stateRunId,contentReviewFingerprint,failure);
                    throw failure;
                }
            }
            Review finalStyleRecheck=styleRecheck;
            String finalStyleRecheckRunId=styleRecheckRunId;
            String finalOutlineWorkspaceId=activeOutlineWorkspaceId;
            StateExtractionOutcome finalStateOutcome=stateOutcome;
            repository.update(id, n -> {
                Task current = find(n, taskId);
                AgentRun agentRun=finalReviewRunId==null?null:findAgentRun(n,finalReviewRunId);
                if (current.status != TaskStatus.RUNNING) {
                    if (agentRun!=null)
                        transitionAgentRun(agentRun,runStatus(current.status),null,null,"任务已停止，检查结果未写入");
                    if(finalStateOutcome!=null)
                        transitionAgentRun(findAgentRun(n,finalStateOutcome.runId()),runStatus(current.status),
                                null,null,"任务已停止，状态提取结果未写入");
                    return null;
                }
                long expectedRevision=task.action==Action.REVIEW || task.action==Action.STYLE_REVIEW || task.action==Action.COMPLETE
                        ? current.inputRevision : current.stagedRevision;
                if (n.revision != expectedRevision) {
                    current.status=TaskStatus.STALE; current.progressStage="STALE"; current.error="输入版本发生变化，结果未写入"; current.finishedAt=Novel.now();
                    if (agentRun!=null) transitionAgentRun(agentRun,AgentRunStatus.STALE,null,null,current.error);
                    if(finalStateOutcome!=null)
                        transitionAgentRun(findAgentRun(n,finalStateOutcome.runId()),AgentRunStatus.STALE,
                                null,null,current.error);
                    return null;
                }
                if (task.action == Action.COMPLETE) {
                    var open = canon.at(n, Integer.MAX_VALUE).stream().filter(e -> e.fact().type().equals("FORESHADOW") && e.fact().state().equals("OPEN")).toList();
                    Review checked = finalReview;
                    if (!open.isEmpty()) {
                        String clues=open.stream().map(e -> e.fact().detail()).toList().toString();
                        checked = finalReview.withIssue(new ReviewIssue("全书完结检查 > 伏笔回收","仍有重要伏笔未回收",clues,
                                "在正文中完成这些伏笔，或由作者确认其不再属于重要伏笔","必须修正"));
                        checked = new Review(false,checked.issues(),finalReview.mainlineResolved(),finalReview.endingClear(),false,checked.issueDetails());
                    }
                    n.completionChecks.add(new Completion(Novel.uid(), n.revision, words.approvedWords(n), checked, Novel.now()));
                } else if (task.action == Action.STYLE_REVIEW) {
                    Artifact a=rules.artifact(n,task.artifactId);
                    a.latest().styleReview=finalReview;
                    a.latest().styleReviewPolicyVersion=StyleReviewPolicy.VERSION;
                    current.resultArtifactId=a.id; current.resultVersionId=a.latest().id;
                } else if (task.action == Action.REVIEW) {
                    Artifact a = rules.artifact(n, task.artifactId);
                    a.latest().review=finalReview; a.latest().reviewRevision=n.revision;
                    a.latest().reviewPolicyVersion=ReviewPolicy.VERSION;
                    a.latest().contentReviewFingerprint=finalContentReviewFingerprint;
                    if(finalStateOutcome!=null) applyStateExtraction(a.latest(),finalStateOutcome);
                    current.resultArtifactId=a.id; current.resultVersionId=a.latest().id;
                } else {
                    Artifact a=rules.artifact(n,current.resultArtifactId);
                    Version v=a.versions.stream().filter(version->version.id.equals(current.resultVersionId)).findFirst()
                            .orElseThrow(()->new Problem(409,"已保存的候选版本不存在，请刷新后重试"));
                    v.review=finalReview; v.reviewRevision=n.revision;
                    v.reviewPolicyVersion=ReviewPolicy.VERSION;
                    v.contentReviewFingerprint=finalContentReviewFingerprint;
                    if (finalStyleRecheck!=null) {
                        v.styleReview=finalStyleRecheck;
                        v.styleReviewPolicyVersion=StyleReviewPolicy.VERSION;
                    }
                    if(finalStateOutcome!=null) applyStateExtraction(v,finalStateOutcome);
                    if (a.kind == Kind.CHAPTER) {
                        long projected=words.approvedWords(n) - (a.approved()==null ? 0 : words.count(a.approved().content)) + words.count(v.content);
                        if (projected > n.approvedMaxWords) {
                            v.review=finalReview.withIssue(new ReviewIssue("当前章节 > 字数预算","确认后将超过已批准字数上限",
                                    "系统按已确认正文和当前候选正文计算的总字数超过上限","压缩正文，或由作者明确批准提高上限","必须修正"));
                        }
                    }
                }
                current.status=TaskStatus.SUCCEEDED; current.progressStage="READY"; current.finishedAt=Novel.now();
                if (agentRun!=null)
                    transitionAgentRun(agentRun,AgentRunStatus.SUCCEEDED,current.resultArtifactId,current.resultVersionId,null);
                if (finalStyleRecheckRunId!=null)
                    transitionAgentRun(findAgentRun(n,finalStyleRecheckRunId),AgentRunStatus.SUCCEEDED,
                            current.resultArtifactId,current.resultVersionId,null);
                if(finalStateOutcome!=null)
                    transitionAgentRun(findAgentRun(n,finalStateOutcome.runId()),AgentRunStatus.SUCCEEDED,
                            current.resultArtifactId,current.resultVersionId,null);
                if(finalOutlineWorkspaceId!=null) outlinePipelines.complete(n,finalOutlineWorkspaceId,finalReview);
                return null;
            });
            boolean eligibleShadow=!stateExtractionOnly && !outlineTarget && task.action!=Action.STYLE_REVIEW
                    && task.action!=Action.COMPLETE && !task.summaryOnly;
            String artifactId=staged!=null?staged.artifactId():target==null?null:target.id;
            String versionId=staged!=null?staged.versionId():target==null||target.latest()==null?null:target.latest().id;
            ModelGateway.Generated downstreamCandidate=finalStateOutcome==null?candidate
                    :new ModelGateway.Generated(candidate.title(),candidate.content(),candidate.summary(),
                    finalStateOutcome.facts(),candidate.plan(),candidate.outlineSpec(),candidate.draftIssues());
            if (outlineTarget)
                queueAutomaticOutlineRepair(id,artifactId,versionId,finalReview,task);
            List<String> shadowUpstream=generationRunId==null?List.of():List.of(generationRunId);
            boolean chapterCandidate=task.action==Action.CHAPTER || (target!=null && target.kind==Kind.CHAPTER);
            if (eligibleShadow && chapterCandidate) {
                continuityShadows.run(id,taskId,source.id,artifactId,versionId,request,downstreamCandidate,
                        shadowUpstream);
                historicalContinuityGray.run(id,taskId,source.id,artifactId,versionId,request,downstreamCandidate,
                        shadowUpstream);
            }
            boolean plotCandidate=List.of(Action.OUTLINE,Action.PLAN,Action.CHAPTER).contains(task.action)
                    || (target!=null && List.of(Kind.OUTLINE,Kind.PLAN,Kind.CHAPTER).contains(target.kind));
            if (eligibleShadow && plotCandidate) {
                plotForeshadowShadows.run(id,taskId,source.id,artifactId,versionId,request,downstreamCandidate,
                        shadowUpstream);
            }
            if (!stateExtractionOnly && !deferredStylePolish && finalReview.passed()
                    && (task.action==Action.CHAPTER
                    || (target!=null && target.kind==Kind.CHAPTER && List.of(Action.REWRITE,Action.REVIEW).contains(task.action))))
                queueAutomaticStyleCheck(id,artifactId,versionId);
            if (task.action==Action.STYLE_REVIEW && AUTO_STYLE_CHECK.equals(task.automationKind))
                queueAutomaticStylePolish(id,task.artifactId);
            activeAgentRunId=null;
        } catch (Exception e) {
            log.warn("Task failed: task={} exception={}",taskId,e.getClass().getSimpleName());
            String failedRunId=activeAgentRunId;
            TaskStatus stoppedStatus=find(repository.get(id),taskId).status;
            if (stoppedStatus==TaskStatus.CANCELLED || stoppedStatus==TaskStatus.STALE || stoppedStatus==TaskStatus.INTERRUPTED)
                stopOutlineWorkspaceForTask(id,taskId,activeOutlineWorkspaceId);
            else outlinePipelines.fail(id,activeOutlineWorkspaceId,e instanceof Problem?e.getMessage():
                    "大纲流水线失败；已完成的候选和步骤记录保持不变");
            repository.update(id, n -> {
                Task t=find(n, taskId);
                if (failedRunId!=null) n.agentRuns.stream()
                        .filter(run->t.id.equals(run.taskId) && run.status==AgentRunStatus.RUNNING)
                        .forEach(run->transitionAgentRun(run,AgentRunStatus.FAILED,null,null,
                                e instanceof Problem?e.getMessage():"Agent 步骤失败"));
                if (t.status == TaskStatus.RUNNING || t.status == TaskStatus.QUEUED) {
                    t.status=TaskStatus.FAILED; t.progressStage="FAILED"; t.finishedAt=Novel.now();
                    String message=e instanceof Problem ? e.getMessage() : "任务失败；原有版本保留，请查看本机日志或重试";
                    if (t.resultVersionId!=null)
                        message+="；候选内容已保存，可单独重新检查或修改，不需要重新生成正文";
                    t.error=message;
                }
                return null;
            });
        }
    }

    private void stopOutlineWorkspaceForTask(String novelId,String taskId,String workspaceId) {
        if (workspaceId==null) return;
        Task task=find(repository.get(novelId),taskId);
        OutlinePipelineStatus status=switch (task.status) {
            case CANCELLED -> OutlinePipelineStatus.CANCELLED;
            case STALE -> OutlinePipelineStatus.STALE;
            case INTERRUPTED -> OutlinePipelineStatus.INTERRUPTED;
            default -> null;
        };
        if (status!=null) outlinePipelines.stop(novelId,workspaceId,status,
                task.error==null?"大纲流水线随父任务停止":task.error);
    }

    private void finishOutlineWithDeterministicIssues(String novelId,String taskId,StagedCandidate staged,
                                                       Artifact target,Review review,String workspaceId) {
        repository.update(novelId,n->{
            Task task=find(n,taskId);
            Artifact artifact=staged!=null?rules.artifact(n,staged.artifactId()):rules.artifact(n,target.id);
            Version version=artifact.latest();
            version.review=review; version.reviewRevision=n.revision;
            version.reviewPolicyVersion=OutlineStructureService.VERSION;
            task.resultArtifactId=artifact.id; task.resultVersionId=version.id;
            task.status=TaskStatus.SUCCEEDED; task.progressStage="READY"; task.finishedAt=Novel.now();
            outlinePipelines.needsInput(n,workspaceId,
                    "结构化大纲未通过确定性检查；候选已经保存，未继续调用专业检查");
            return null;
        });
    }
    private void queueAutomaticOutlineRepair(String novelId,String artifactId,String versionId,
                                             Review review,Task sourceTask) {
        try {
            if (sourceTask==null || (sourceTask.automationKind!=null && !sourceTask.automationKind.isBlank())
                    || !List.of(Action.OUTLINE,Action.REWRITE).contains(sourceTask.action)
                    || review==null || review.issueDetails()==null) return;
            List<ReviewIssue> required=review.issueDetails().stream()
                    .filter(issue->"必须修正".equals(issue.severity())).toList();
            if (required.isEmpty()) return;
            Novel novel=repository.get(novelId);
            Artifact artifact=rules.artifact(novel,artifactId); Version version=artifact.latest();
            if (artifact.kind!=Kind.OUTLINE || artifact.clean() || version==null || !version.id.equals(versionId)
                    || version.outlineSpec==null || version.outlineAutoRepairRound>=1) return;
            StringBuilder instructions=new StringBuilder("这是系统授权的唯一一轮大纲自动修订（1/1）。")
                    .append("只修正下面列出的必须修正问题；不得处理作者决定或建议优化项，不得自动确认版本。")
                    .append("保留不受影响的故事核心、结局、人物、事件、伏笔、稳定编号和字数预算；")
                    .append("如果局部修正影响引用或证据链，只同步调整直接相关字段：\n");
            for (int i=0;i<required.size();i++) {
                ReviewIssue issue=required.get(i);
                instructions.append(i+1).append(". 修改位置：").append(issue.location())
                        .append("；问题：").append(issue.problem()).append("；依据：").append(issue.evidence())
                        .append("；建议：").append(issue.suggestion()).append('\n');
            }
            submit(novelId,Action.REWRITE,artifactId,instructions.toString().strip(),Novel.uid(),novel.revision,
                    false,OUTLINE_AUTO_REPAIR,1);
        } catch (Problem problem) {
            log.warn("Automatic outline repair was not queued: novel={} artifact={} reason={}",
                    novelId,artifactId,problem.getClass().getSimpleName());
        }
    }
    private void queueAutomaticStyleCheck(String novelId,String artifactId,String versionId) {
        try {
            Novel novel=repository.get(novelId);
            if (!Boolean.TRUE.equals(novel.autoStyleEnabled) || artifactId==null || versionId==null) return;
            Artifact artifact=rules.artifact(novel,artifactId); Version version=artifact.latest();
            if (artifact.kind!=Kind.CHAPTER || artifact.clean() || version==null || !versionId.equals(version.id)
                    || version.stylePolishRound>=1 || !rules.reviewCurrent(novel,version) || !version.review.passed()) return;
            if (version.styleReview!=null && StyleReviewPolicy.VERSION.equals(version.styleReviewPolicyVersion)) return;
            submit(novelId,Action.STYLE_REVIEW,artifactId,
                    "章节内容检查已通过；执行默认文风检查。只提出有当前原文证据的局部建议，不改变剧情和事实。",
                    Novel.uid(),novel.revision,false,AUTO_STYLE_CHECK,0);
        } catch (Problem problem) {
            log.warn("Automatic style check was not queued: novel={} artifact={} reason={}",
                    novelId,artifactId,problem.getClass().getSimpleName());
        }
    }
    private void queueAutomaticStylePolish(String novelId,String artifactId) {
        try {
            Novel novel=repository.get(novelId);
            if (!Boolean.TRUE.equals(novel.autoStyleEnabled)) return;
            Artifact artifact=rules.artifact(novel,artifactId); Version version=artifact.latest();
            if (artifact.clean() || version==null || version.stylePolishRound>=1
                    || !rules.reviewCurrent(novel,version) || !version.review.passed()
                    || version.styleReview==null || !StyleReviewPolicy.VERSION.equals(version.styleReviewPolicyVersion)
                    || !styleEditor.canApply(version)) return;
            submitStylePolish(novelId,artifactId,Novel.uid(),novel.revision);
        } catch (Problem problem) {
            log.warn("Automatic style polish was not queued: novel={} artifact={} reason={}",
                    novelId,artifactId,problem.getClass().getSimpleName());
        }
    }
    private StagedCandidate stageCandidate(String id,String taskId,Task task,ContextAssembler.Context context,
                                            ModelGateway.Generated result,String agentRunId,String outlineWorkspaceId) {
        return repository.update(id,n->{
            Task current=find(n,taskId);
            AgentRun agentRun=findAgentRun(n,agentRunId);
            if (current.status!=TaskStatus.RUNNING) {
                transitionAgentRun(agentRun,runStatus(current.status),null,null,"任务已停止，生成结果未写入");
                return null;
            }
            if (n.revision!=current.inputRevision) {
                current.status=TaskStatus.STALE; current.progressStage="STALE"; current.error="输入版本发生变化，结果未写入"; current.finishedAt=Novel.now();
                transitionAgentRun(agentRun,AgentRunStatus.STALE,null,null,current.error); return null;
            }
            Artifact artifact;
            if (task.action==Action.REWRITE) {
                artifact=rules.artifact(n,task.artifactId);
                boolean changed=impact.changed(artifact.latest(),result.title(),result.content(),result.summary(),result.facts(),result.plan());
                if (artifact.kind==Kind.OUTLINE && result.outlineSpec()!=null)
                    changed=changed || !Objects.equals(artifact.latest().outlineSpecHash,outlineStructures.hash(result.outlineSpec()));
                require(changed,
                        "模型没有产生实际修改；原版本保持不变，请补充更具体的修改要求");
            } else {
                artifact=new Artifact(); artifact.kind=Kind.valueOf(task.action.name());
                if (artifact.kind==Kind.CHAPTER) artifact.chapterNumber=context.chapterNumber();
                if (artifact.kind==Kind.PLAN) artifact.batchNumber=context.batchNumber();
            }
            Version version=new Version(); version.baseVersionId=artifact.latest()==null?null:artifact.latest().id;
            Version baseVersion=artifact.latest();
            version.title=result.title(); version.content=result.content(); version.summary=result.summary();
            version.facts=result.facts(); version.plan=result.plan(); version.outlineSpec=result.outlineSpec();
            version.outlineSpecHash=result.outlineSpec()==null?null:outlineStructures.hash(result.outlineSpec());
            version.draftIssues=new ArrayList<>(result.draftIssues());
            if (CONTENT_FIX.equals(task.automationKind)) {
                version.contentFixRound=task.automationRound; version.stylePolishRound=0;
            } else if (STYLE_POLISH.equals(task.automationKind)) {
                version.contentFixRound=baseVersion==null?0:baseVersion.contentFixRound;
                version.stylePolishRound=task.automationRound;
            } else if (OUTLINE_AUTO_REPAIR.equals(task.automationKind)) {
                version.outlineAutoRepairRound=task.automationRound;
            }
            version.source=STYLE_POLISH.equals(task.automationKind)?"SYSTEM":agents.mode().equals("demo")?"DEMO":"MODEL";
            version.basedOnRevision=n.revision; version.sourceVersionIds=context.sourceVersions();
            version.previousNeedsRevision=artifact.needsRevision;
            prepareChapterState(artifact,baseVersion,version);
            validateStagedVersion(n,artifact,version);
            if (task.action==Action.REWRITE && !OUTLINE_AUTO_REPAIR.equals(task.automationKind))
                impact.invalidateFollowing(n,artifact,task.instructions);
            artifact.versions.add(version); artifact.needsRevision=false;
            if (task.action!=Action.REWRITE) n.artifacts.add(artifact);
            n.revision++;
            current.resultArtifactId=artifact.id; current.resultVersionId=version.id; current.stagedRevision=n.revision;
            if(outlineWorkspaceId!=null)
                outlinePipelines.saveCandidate(n,outlineWorkspaceId,artifact.id,version.id);
            transitionAgentRun(agentRun,AgentRunStatus.SUCCEEDED,artifact.id,version.id,null);
            boolean needsReview=version.draftIssues.isEmpty();
            if (!needsReview) {
                version.review=incompleteDraftReview(version.draftIssues); version.reviewRevision=n.revision;
                current.status=TaskStatus.SUCCEEDED; current.progressStage="READY"; current.finishedAt=Novel.now();
                if(outlineWorkspaceId!=null) outlinePipelines.needsInput(n,outlineWorkspaceId,
                        "大纲候选已经保存，但结构化摘要或档案信息不完整；补全后可单独重新检查");
            }
            return new StagedCandidate(artifact.id,version.id,n.revision,needsReview);
        });
    }
    private void validateStagedVersion(Novel novel,Artifact artifact,Version version) {
        if (version.draftIssues.isEmpty()) { rules.validateVersion(novel,artifact,version); return; }
        String originalSummary=version.summary;
        try {
            version.summary="待补全的内容摘要";
            rules.validateVersion(novel,artifact,version);
        } finally { version.summary=originalSummary; }
    }

    private ModelGateway.Generated withoutWriterFacts(ModelGateway.Generated candidate) {
        return new ModelGateway.Generated(candidate.title(),candidate.content(),candidate.summary(),List.of(),
                candidate.plan(),candidate.outlineSpec(),candidate.draftIssues());
    }

    private void prepareChapterState(Artifact artifact,Version base,Version version) {
        if(artifact.kind!=Kind.CHAPTER) return;
        version.stateExtractionRequired=true;
        if(base!=null && Objects.equals(base.content,version.content) && stateExtractions.current(base)) {
            version.facts=base.facts==null?List.of():List.copyOf(base.facts);
            version.stateExtractionStatus=StateExtractionStatus.SUCCEEDED;
            version.stateExtractionPolicyVersion=base.stateExtractionPolicyVersion;
            version.stateExtractionSourceHash=base.stateExtractionSourceHash;
            version.stateExtractionAgentRunId=base.stateExtractionAgentRunId;
            version.stateEvidence=base.stateEvidence==null?new ArrayList<>():new ArrayList<>(base.stateEvidence);
            version.stateEntities=base.stateEntities==null?new ArrayList<>():new ArrayList<>(base.stateEntities);
            version.stateRelations=base.stateRelations==null?new ArrayList<>():new ArrayList<>(base.stateRelations);
            return;
        }
        version.facts=List.of();
        version.stateExtractionStatus=StateExtractionStatus.PENDING;
        version.stateExtractionPolicyVersion=null; version.stateExtractionSourceHash=null;
        version.stateExtractionAgentRunId=null; version.stateExtractionError=null;
        version.stateEvidence=new ArrayList<>();
        version.stateEntities=new ArrayList<>(); version.stateRelations=new ArrayList<>();
    }

    private void markStateExtractionRunning(String novelId,String artifactId,String versionId,String runId) {
        repository.update(novelId,n->{
            Version version=version(rules.artifact(n,artifactId),versionId);
            version.stateExtractionRequired=true; version.stateExtractionStatus=StateExtractionStatus.PENDING;
            version.stateExtractionAgentRunId=runId; version.stateExtractionError=null;
            return null;
        });
    }

    private void preserveReviewAndMarkStateExtractionFailed(String novelId,String taskId,String artifactId,
                                                              String versionId,Review review,String reviewRunId,
                                                              String stateRunId,String contentReviewFingerprint,
                                                              Exception failure) {
        repository.update(novelId,n->{
            Task task=find(n,taskId);
            Version version=version(rules.artifact(n,artifactId),versionId);
            long expectedRevision=task.action==Action.REVIEW || task.action==Action.STYLE_REVIEW
                    || task.action==Action.COMPLETE ? task.inputRevision : task.stagedRevision;
            if(task.status==TaskStatus.RUNNING && n.revision==expectedRevision) {
                version.review=review;
                version.reviewRevision=n.revision;
                version.reviewPolicyVersion=ReviewPolicy.VERSION;
                version.contentReviewFingerprint=contentReviewFingerprint;
                task.resultArtifactId=artifactId;
                task.resultVersionId=versionId;
                if(reviewRunId!=null)
                    transitionAgentRun(findAgentRun(n,reviewRunId),AgentRunStatus.SUCCEEDED,
                            artifactId,versionId,null);
            }
            version.stateExtractionRequired=true; version.stateExtractionStatus=StateExtractionStatus.FAILED;
            version.stateExtractionAgentRunId=stateRunId;
            version.stateExtractionError=failure instanceof Problem?failure.getMessage()
                    :"独立状态提取失败；正文草稿已经保留，可以只重试状态提取";
            return null;
        });
    }

    private Review reusableContentReview(Artifact target,String fingerprint) {
        if(target==null || fingerprint==null) return null;
        for(int index=target.versions.size()-1;index>=0;index--) {
            Version version=target.versions.get(index);
            if(version.review!=null && ReviewPolicy.VERSION.equals(version.reviewPolicyVersion)
                    && fingerprint.equals(version.contentReviewFingerprint)) return version.review;
        }
        return null;
    }

    private void applyStateExtraction(Version version,StateExtractionOutcome outcome) {
        version.facts=List.copyOf(outcome.facts());
        version.stateEntities=new ArrayList<>(outcome.entities());
        version.stateRelations=new ArrayList<>(outcome.relations());
        version.stateEvidence=new ArrayList<>(outcome.evidence());
        version.stateExtractionRequired=true; version.stateExtractionStatus=StateExtractionStatus.SUCCEEDED;
        version.stateExtractionPolicyVersion=StateExtractionPolicy.VERSION;
        version.stateExtractionSourceHash=outcome.sourceHash();
        version.stateExtractionAgentRunId=outcome.runId(); version.stateExtractionError=null;
    }

    private Version version(Artifact artifact,String versionId) {
        return artifact.versions.stream().filter(item->versionId.equals(item.id)).findFirst()
                .orElseThrow(()->new Problem(409,"状态提取对应的正文版本不存在"));
    }
    private Review incompleteDraftReview(List<String> issues) {
        List<ReviewIssue> details=issues.stream().map(issue->{
            boolean summary=issue.contains("摘要");
            String location=summary?"当前内容 > 内容摘要":"当前内容 > 档案增量";
            String evidence=summary?"模型返回了可阅读正文，但没有返回可供后续创作使用的完整摘要"
                    :"模型返回了可阅读正文，但没有返回可供连续创作使用的档案事实增量";
            String suggestion=summary?"手动补全摘要，或让 Agent 只补充摘要，然后重新检查"
                    :"手动补充档案增量，或让 Agent 只补充正文中已经发生的事实变化，然后重新检查";
            return new ReviewIssue(location,issue,evidence,suggestion,"必须修正");
        }).toList();
        return new Review(false,details.stream().map(ReviewIssue::text).toList(),false,false,false,details);
    }
    private void validateSummaryOnly(Artifact target,ModelGateway.Generated candidate) {
        require(target!=null && target.latest()!=null, "补充摘要的目标版本不存在");
        Version base=target.latest();
        require(candidate.summary()!=null && !candidate.summary().isBlank(), "模型没有补充有效摘要；原草稿保持不变");
        require(Objects.equals(base.title,candidate.title()) && Objects.equals(base.content,candidate.content())
                        && Objects.equals(base.facts,candidate.facts()) && Objects.equals(base.plan,candidate.plan())
                        && Objects.equals(base.outlineSpecHash,candidate.outlineSpec()==null?null:outlineStructures.hash(candidate.outlineSpec())),
                "本次任务只允许补充摘要，但模型还修改了其他内容；原草稿保持不变");
    }
    private SourceSnapshot sourceSnapshot(Novel novel,String sourceSnapshotId) {
        require(sourceSnapshotId!=null && novel.sourceSnapshots!=null,"任务缺少来源快照，不能调用模型");
        return novel.sourceSnapshots.stream().filter(item->sourceSnapshotId.equals(item.id)).findFirst()
                .orElseThrow(()->new Problem(409,"任务来源快照不存在，不能调用模型"));
    }
    private String startAgentRun(String novelId,String taskId,String sourceSnapshotId,AgentRole role,String operation,
                                 String inputVersionId,List<String> upstreamAgentRunIds,ContextAssembler.Context context) {
        return repository.update(novelId,n->{
            Task task=find(n,taskId);
            require(task.status==TaskStatus.RUNNING,"任务已经停止，不能开始新的 Agent 步骤");
            AgentRun run=new AgentRun(); run.taskId=taskId; run.sourceSnapshotId=sourceSnapshotId;
            run.role=role.name(); run.operation=operation; run.inputVersionId=inputVersionId;
            run.contextPolicyVersion=AgentContextPolicy.VERSION;
            run.contextJson=context.json(); run.contextHash=snapshots.hash(context.json());
            run.contextSourceVersionIds=new ArrayList<>(context.sourceVersions());
            run.contextChapterNumber=context.chapterNumber(); run.contextBatchNumber=context.batchNumber();
            run.upstreamAgentRunIds=new ArrayList<>(upstreamAgentRunIds);
            n.agentRuns.add(run); task.agentRunIds.add(run.id); return run.id;
        });
    }
    private void completeAgentRun(String novelId,String runId,String artifactId,String versionId) {
        repository.update(novelId,n->{
            transitionAgentRun(findAgentRun(n,runId),AgentRunStatus.SUCCEEDED,artifactId,versionId,null);
            return null;
        });
    }
    private AgentRun findAgentRun(Novel novel,String runId) {
        require(runId!=null && novel.agentRuns!=null,"Agent 调用记录不存在");
        return novel.agentRuns.stream().filter(run->runId.equals(run.id)).findFirst()
                .orElseThrow(()->new Problem(409,"Agent 调用记录不存在"));
    }
    private void progress(String novelId,String taskId,String stage) {
        repository.update(novelId,n->{
            Task task=find(n,taskId);
            if (task.status==TaskStatus.RUNNING) task.progressStage=stage;
            return null;
        });
    }
    private void transitionAgentRun(AgentRun run,AgentRunStatus status,String resultArtifactId,String resultVersionId,String error) {
        if (run.status!=AgentRunStatus.RUNNING) return;
        run.status=status; run.resultArtifactId=resultArtifactId; run.resultVersionId=resultVersionId;
        run.error=error; run.finishedAt=Novel.now();
    }
    private AgentRunStatus runStatus(TaskStatus status) {
        return switch (status) {
            case CANCELLED -> AgentRunStatus.CANCELLED;
            case STALE -> AgentRunStatus.STALE;
            case INTERRUPTED -> AgentRunStatus.INTERRUPTED;
            case SUCCEEDED -> AgentRunStatus.SUCCEEDED;
            default -> AgentRunStatus.FAILED;
        };
    }
    public Task cancel(String id, String taskId) {
        Task t = repository.update(id, n -> {
            Task task=find(n, taskId);
            if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.QUEUED) {
                task.status=TaskStatus.CANCELLED; task.progressStage="CANCELLED"; task.finishedAt=Novel.now();
                n.agentRuns.stream().filter(run->task.id.equals(run.taskId) && run.status==AgentRunStatus.RUNNING)
                        .forEach(run->transitionAgentRun(run,AgentRunStatus.CANCELLED,null,null,"任务由用户取消"));
            }
            return task;
        });
        FutureTask<Void> future=running.remove(taskId); if (future != null) future.cancel(true);
        return t;
    }
    public Task retry(String id, String taskId, String requestKey, long revision) {
        Novel novel=repository.get(id);
        Task old=find(novel, taskId);
        require(List.of(TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.STALE, TaskStatus.INTERRUPTED).contains(old.status), "只有失败、中断、取消或过期的任务可以重试");
        if (old.resultArtifactId!=null && old.resultVersionId!=null) {
            Artifact artifact=rules.artifact(novel,old.resultArtifactId);
            if (artifact.latest()!=null && old.resultVersionId.equals(artifact.latest().id) && artifact.latest().review==null)
                return submit(id,Action.REVIEW,artifact.id,old.instructions,requestKey,revision);
        }
        return submit(id,old.action,old.artifactId,old.instructions,requestKey,revision,
                old.summaryOnly,old.automationKind,old.automationRound);
    }
    public Task find(Novel n, String id) { return n.tasks.stream().filter(t -> t.id.equals(id)).findFirst().orElseThrow(() -> new Problem(404, "任务不属于该小说或不存在")); }
    @EventListener(ApplicationStartedEvent.class)
    public void recover() {
        for (Novel n : repository.list()) repository.update(n.id, current -> {
            if (current.agentRuns==null) current.agentRuns=new ArrayList<>();
            if (current.shadowReviews==null) current.shadowReviews=new ArrayList<>();
            outlinePipelines.ensure(current);
            current.agentRuns.stream().filter(run->AgentRole.STATE_EXTRACTOR.name().equals(run.role)
                    && run.status==AgentRunStatus.RUNNING && run.inputVersionId!=null).forEach(run->{
                current.artifacts.stream().flatMap(artifact->artifact.versions.stream())
                        .filter(version->run.inputVersionId.equals(version.id)).findFirst().ifPresent(version->{
                            version.stateExtractionRequired=true;
                            version.stateExtractionStatus=StateExtractionStatus.FAILED;
                            version.stateExtractionAgentRunId=run.id;
                            version.stateExtractionError="服务重启导致状态提取中断；正文草稿和内容检查均已保留，可以只重试状态提取";
                        });
            });
            current.tasks.stream().filter(t -> t.status == TaskStatus.RUNNING || t.status == TaskStatus.QUEUED).forEach(t -> {
                t.status=TaskStatus.INTERRUPTED; t.progressStage="INTERRUPTED"; t.finishedAt=Novel.now(); t.error="服务重启导致任务中断；请手动重试，避免自动重复计费";
                current.agentRuns.stream().filter(run->t.id.equals(run.taskId) && run.status==AgentRunStatus.RUNNING)
                        .forEach(run->transitionAgentRun(run,AgentRunStatus.INTERRUPTED,null,null,t.error));
            });
            current.agentRuns.stream().filter(run->run.status==AgentRunStatus.RUNNING)
                    .forEach(run->transitionAgentRun(run,AgentRunStatus.INTERRUPTED,null,null,"服务重启导致影子检查中断；正式结果不受影响"));
            current.shadowReviews.stream().filter(review->review.status==ShadowReviewStatus.RUNNING).forEach(review->{
                review.status=ShadowReviewStatus.INTERRUPTED; review.error="服务重启导致影子检查中断；正式结果不受影响";
                review.finishedAt=Novel.now();
            });
            current.outlinePipelines.stream().filter(workspace->workspace.status==OutlinePipelineStatus.RUNNING)
                    .forEach(workspace->{
                        workspace.status=OutlinePipelineStatus.INTERRUPTED;
                        workspace.error="服务重启导致大纲流水线中断；已保存候选和已完成步骤保持不变";
                        workspace.finishedAt=Novel.now();
                    });
            return null;
        });
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
