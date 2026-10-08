package com.novelforge.generation;

import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.shared.Problem;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

import static com.novelforge.shared.Problem.require;

/**
 * Replays the fixed historical evaluation sample under the current professional-review policy.
 * Results live in a separate collection and cannot mutate content, confirmation, revision or v1 reports.
 */
@Service
public class ProfessionalReviewReplayService {
    private static final Logger log=LoggerFactory.getLogger(ProfessionalReviewReplayService.class);
    private final NovelRepository repository;
    private final ShadowReviewEvaluationService evaluations;
    private final SourceSnapshotFactory snapshots;
    private final AgentOrchestrator agents;
    private final ReviewAggregator reviews;
    private final ProfessionalReviewPolicy policy;
    private final ContinuityReviewPolicy continuityPolicy;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{
        Thread thread=new Thread(r,"professional-review-replay"); thread.setDaemon(true); return thread;
    });
    private final ConcurrentHashMap<String,FutureTask<Void>> running=new ConcurrentHashMap<>();

    public ProfessionalReviewReplayService(NovelRepository repository,
                                           ShadowReviewEvaluationService evaluations,
                                           SourceSnapshotFactory snapshots,
                                           AgentOrchestrator agents,
                                           ReviewAggregator reviews,
                                           ProfessionalReviewPolicy policy,
                                           ContinuityReviewPolicy continuityPolicy) {
        this.repository=repository; this.evaluations=evaluations; this.snapshots=snapshots;
        this.agents=agents; this.reviews=reviews; this.policy=policy; this.continuityPolicy=continuityPolicy;
    }

    public ProfessionalReviewReplayBatch start(String novelId,String requestKey) {
        return start(novelId,requestKey,null,20);
    }

    public ProfessionalReviewReplayBatch start(String novelId,String requestKey,String checker) {
        return start(novelId,requestKey,checker,20);
    }

    public ProfessionalReviewReplayBatch start(String novelId,String requestKey,String checker,int sampleLimit) {
        require(requestKey!=null && !requestKey.isBlank() && requestKey.length()<=100,
                "对照重放需要不超过100字符的幂等键");
        require(checker==null || List.of(ContinuityShadowService.CHECKER,PlotForeshadowShadowService.CHECKER).contains(checker),
                "专业检查器只能选择连续性检查或情节与伏笔检查");
        require(sampleLimit>=1 && sampleLimit<=20,"专业检查对照样本数必须在 1 到 20 之间");
        require(agents.ready(),"真实模型尚未配置，不能开始专业检查对照重放");
        Novel snapshot=repository.get(novelId);
        List<String> sampleIds=evaluations.summary(snapshot).reports().stream()
                .filter(ShadowReviewEvaluationService.ReportView::evaluationSample)
                .filter(report->checker==null || checker.equals(report.checker()))
                .map(ShadowReviewEvaluationService.ReportView::id).limit(sampleLimit).toList();
        require(!sampleIds.isEmpty(),"当前小说没有可用于对照重放的固定历史样本");

        boolean[] created={false};
        ProfessionalReviewReplayBatch batch=repository.update(novelId,novel->{
            if (novel.professionalReviewReplays==null) novel.professionalReviewReplays=new ArrayList<>();
            ProfessionalReviewReplayBatch existing=novel.professionalReviewReplays.stream()
                    .filter(item->requestKey.equals(item.requestKey)).findFirst().orElse(null);
            if (existing!=null) {
                require(replayVersion(checker).equals(existing.policyVersion),
                        "同一幂等键不能用于不同的专业检查策略");
                require(java.util.Objects.equals(checker,existing.checker),
                        "同一幂等键不能用于不同的专业检查器");
                require(existing.sampleLimit==sampleLimit,"同一幂等键不能用于不同的样本数量");
                return existing;
            }
            ProfessionalReviewReplayBatch added=new ProfessionalReviewReplayBatch();
            added.requestKey=requestKey; added.policyVersion=replayVersion(checker); added.checker=checker;
            added.sampleLimit=sampleLimit;
            for (String baselineId:sampleIds) added.items.add(prepareItem(novel,baselineId));
            novel.professionalReviewReplays.add(added); created[0]=true; return added;
        });
        if (created[0]) launch(novelId,batch.id);
        return batch;
    }

    public List<ProfessionalReviewReplayBatch> list(String novelId) {
        Novel novel=repository.get(novelId);
        return novel.professionalReviewReplays==null?List.of():List.copyOf(novel.professionalReviewReplays);
    }

    public ProfessionalReviewReplayBatch find(String novelId,String batchId) {
        return findBatch(repository.get(novelId),batchId);
    }

    private ProfessionalReviewReplayItem prepareItem(Novel novel,String baselineId) {
        ShadowReview baseline=findBaseline(novel,baselineId);
        require(baseline.status==ShadowReviewStatus.SUCCEEDED,"固定样本包含未成功的历史报告");
        require(baseline.sourceSnapshotId!=null,"固定样本缺少来源快照，不能精确重放");
        source(novel,baseline.sourceSnapshotId);
        Artifact artifact=artifact(novel,baseline.artifactId);
        version(artifact,baseline.versionId);
        ProfessionalReviewReplayItem item=new ProfessionalReviewReplayItem();
        item.baselineReviewId=baseline.id; item.checker=baseline.checker;
        item.artifactId=baseline.artifactId; item.versionId=baseline.versionId;
        item.sourceSnapshotId=baseline.sourceSnapshotId;
        return item;
    }

    private void launch(String novelId,String batchId) {
        FutureTask<Void> future=new FutureTask<>(()->{ try { execute(novelId,batchId); }
            finally { running.remove(batchId); } return null; });
        if (running.putIfAbsent(batchId,future)==null) executor.execute(future);
    }

    private void execute(String novelId,String batchId) {
        List<String> itemIds=find(novelId,batchId).items.stream()
                .filter(item->item.status==ReplayItemStatus.QUEUED).map(item->item.id).toList();
        for (String itemId:itemIds) executeItem(novelId,batchId,itemId);
        repository.update(novelId,novel->{
            ProfessionalReviewReplayBatch batch=findBatch(novel,batchId);
            boolean anyFailed=batch.items.stream().anyMatch(item->item.status==ReplayItemStatus.FAILED);
            boolean anyInterrupted=batch.items.stream().anyMatch(item->item.status==ReplayItemStatus.INTERRUPTED);
            batch.status=anyInterrupted?ShadowReviewStatus.INTERRUPTED
                    :anyFailed?ShadowReviewStatus.FAILED:ShadowReviewStatus.SUCCEEDED;
            batch.finishedAt=Novel.now(); return null;
        });
    }

    private void executeItem(String novelId,String batchId,String itemId) {
        try {
            repository.update(novelId,novel->{
                ProfessionalReviewReplayItem item=findItem(findBatch(novel,batchId),itemId);
                require(item.status==ReplayItemStatus.QUEUED,"对照重放项目状态已变化");
                item.status=ReplayItemStatus.RUNNING; item.modelStartedAt=Novel.now(); return null;
            });
            Novel novel=repository.get(novelId);
            ProfessionalReviewReplayItem item=findItem(findBatch(novel,batchId),itemId);
            ShadowReview baseline=findBaseline(novel,item.baselineReviewId);
            Artifact artifact=artifact(novel,item.artifactId); Version version=version(artifact,item.versionId);
            SourceSnapshot source=source(novel,item.sourceSnapshotId);
            ContextAssembler.Context context=snapshots.restore(source);
            Task task=novel.tasks.stream().filter(value->value.id.equals(baseline.taskId)).findFirst().orElse(null);
            String instructions=task==null?"":task.instructions;
            ModelGateway.Request request=new ModelGateway.Request(source.action,novel,artifact,context,instructions);
            ModelGateway.Generated candidate=new ModelGateway.Generated(version.title,version.content,version.summary,
                    version.facts,version.plan,version.draftIssues);
            AgentRole role=ContinuityShadowService.CHECKER.equals(item.checker)
                    ?AgentRole.CONTINUITY_AUDITOR:AgentRole.PLOT_FORESHADOW_AUDITOR;
            ModelGateway.Request isolated=agents.contextFor(request,role);
            repository.update(novelId,current->{
                ProfessionalReviewReplayItem saved=findItem(findBatch(current,batchId),itemId);
                saved.baselineContextChars=context.json().length();
                saved.roleContextChars=isolated.context().json().length();
                saved.roleContextHash=snapshots.hash(isolated.context().json());
                saved.contextPolicyVersion=AgentContextPolicy.VERSION;
                return null;
            });
            Review raw=switch (item.checker) {
                case ContinuityShadowService.CHECKER -> agents.continuityReview(isolated,candidate);
                case PlotForeshadowShadowService.CHECKER -> agents.plotForeshadowReview(isolated,candidate);
                default -> throw new Problem(409,"历史样本包含未知的专业检查器");
            };
            repository.update(novelId,current->{
                findItem(findBatch(current,batchId),itemId).rawReview=raw; return null;
            });
            Review normalized=policy.normalize(reviews.aggregateShadow(raw,candidate),candidate,isolated,version.review);
            if (ContinuityShadowService.CHECKER.equals(item.checker)) normalized=continuityPolicy.normalize(normalized);
            Review finalReview=normalized;
            repository.update(novelId,current->{
                ProfessionalReviewReplayItem saved=findItem(findBatch(current,batchId),itemId);
                saved.review=finalReview; saved.status=ReplayItemStatus.SUCCEEDED; saved.finishedAt=Novel.now(); return null;
            });
        } catch (Exception e) {
            log.warn("Professional review replay failed: batch={} item={} exception={}",
                    batchId,itemId,e.getClass().getSimpleName());
            repository.update(novelId,novel->{
                ProfessionalReviewReplayItem item=findItem(findBatch(novel,batchId),itemId);
                item.status=ReplayItemStatus.FAILED;
                item.error=e instanceof Problem?e.getMessage():"专业检查对照重放失败；旧报告和小说内容不受影响";
                item.finishedAt=Novel.now(); return null;
            });
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for (Novel novel:repository.list()) {
            if (novel.professionalReviewReplays==null) continue;
            repository.update(novel.id,current->{
                for (ProfessionalReviewReplayBatch batch:current.professionalReviewReplays) {
                    boolean interrupted=false;
                    for (ProfessionalReviewReplayItem item:batch.items) if (item.status==ReplayItemStatus.RUNNING) {
                        item.status=ReplayItemStatus.INTERRUPTED;
                        item.error="服务重启时模型调用尚未完成；为避免重复计费，未自动重试";
                        item.finishedAt=Novel.now(); interrupted=true;
                    }
                    if (interrupted) { batch.status=ShadowReviewStatus.INTERRUPTED; batch.finishedAt=Novel.now(); }
                }
                return null;
            });
        }
    }

    private ProfessionalReviewReplayBatch findBatch(Novel novel,String id) {
        require(novel.professionalReviewReplays!=null,"专业检查对照批次不存在");
        return novel.professionalReviewReplays.stream().filter(item->id.equals(item.id)).findFirst()
                .orElseThrow(()->new Problem(404,"专业检查对照批次不存在"));
    }
    private ProfessionalReviewReplayItem findItem(ProfessionalReviewReplayBatch batch,String id) {
        return batch.items.stream().filter(item->id.equals(item.id)).findFirst()
                .orElseThrow(()->new Problem(404,"专业检查对照项目不存在"));
    }
    private ShadowReview findBaseline(Novel novel,String id) {
        require(novel.shadowReviews!=null,"历史专业检查报告不存在");
        return novel.shadowReviews.stream().filter(item->id.equals(item.id)).findFirst()
                .orElseThrow(()->new Problem(404,"历史专业检查报告不存在"));
    }
    private SourceSnapshot source(Novel novel,String id) {
        require(novel.sourceSnapshots!=null,"历史来源快照不存在");
        return novel.sourceSnapshots.stream().filter(item->id.equals(item.id)).findFirst()
                .orElseThrow(()->new Problem(409,"历史来源快照不存在，不能精确重放"));
    }
    private Artifact artifact(Novel novel,String id) {
        return novel.artifacts.stream().filter(item->id.equals(item.id)).findFirst()
                .orElseThrow(()->new Problem(409,"历史候选所属内容不存在"));
    }
    private Version version(Artifact artifact,String id) {
        return artifact.versions.stream().filter(item->id.equals(item.id)).findFirst()
                .orElseThrow(()->new Problem(409,"历史候选版本不存在"));
    }

    private String replayVersion(String checker) {
        if (ContinuityShadowService.CHECKER.equals(checker)) return ContinuityReviewPolicy.VERSION+"+"+AgentContextPolicy.VERSION;
        if (PlotForeshadowShadowService.CHECKER.equals(checker)) return PlotForeshadowShadowService.POLICY_VERSION+"+"+AgentContextPolicy.VERSION;
        return ContinuityReviewPolicy.VERSION+"+"+PlotForeshadowShadowService.POLICY_VERSION+"+"+AgentContextPolicy.VERSION;
    }

    @PreDestroy public void close() { executor.shutdownNow(); }
}
