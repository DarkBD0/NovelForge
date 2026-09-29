package com.novelforge.generation;

import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.AgentRun;
import com.novelforge.novel.Novel.AgentRunStatus;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ShadowReview;
import com.novelforge.novel.Novel.ShadowReviewStatus;
import com.novelforge.shared.Problem;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static com.novelforge.shared.Problem.require;

/** Shared single-queue lifecycle for all non-blocking professional shadow checks. */
@Component
public class ShadowReviewRunner {
    public record Spec(String checker,String policyVersion,AgentRole role,String operation,String failureMessage) {}
    private record Pending(String reportId,String runId) {}

    private static final Logger log=LoggerFactory.getLogger(ShadowReviewRunner.class);
    private final NovelRepository repository;
    private final ReviewAggregator reviews;
    private final ProfessionalReviewPolicy professionalPolicy;
    private final ContinuityReviewPolicy continuityPolicy;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{
        Thread thread=new Thread(r,"professional-shadow"); thread.setDaemon(true); return thread;
    });

    public ShadowReviewRunner(NovelRepository repository,ReviewAggregator reviews,
                              ProfessionalReviewPolicy professionalPolicy,
                              ContinuityReviewPolicy continuityPolicy) {
        this.repository=repository; this.reviews=reviews; this.professionalPolicy=professionalPolicy;
        this.continuityPolicy=continuityPolicy;
    }

    public void run(Spec spec,String novelId,String taskId,String sourceSnapshotId,String artifactId,String versionId,
                    List<String> upstreamAgentRunIds,ModelGateway.Request request,
                    ModelGateway.Generated candidate,Supplier<Review> modelCall) {
        if (artifactId==null || versionId==null || modelCall==null) return;
        Pending pending;
        try {
            pending=repository.update(novelId,n->prepare(spec,n,taskId,sourceSnapshotId,artifactId,versionId,
                    upstreamAgentRunIds==null?List.of():upstreamAgentRunIds));
        } catch (Exception e) {
            log.warn("Shadow review could not be queued: checker={} task={} exception={}",
                    spec.checker(),taskId,e.getClass().getSimpleName());
            return;
        }
        if (pending==null) return;
        try { executor.execute(()->execute(spec,novelId,taskId,artifactId,versionId,pending,request,candidate,modelCall)); }
        catch (Exception e) { fail(spec,novelId,taskId,pending,e); }
    }

    private void execute(Spec spec,String novelId,String taskId,String artifactId,String versionId,Pending pending,
                         ModelGateway.Request request,ModelGateway.Generated candidate,Supplier<Review> modelCall) {
        try {
            repository.update(novelId,n->{
                ShadowReview shadow=findShadow(n,pending.reportId());
                if (shadow.modelStartedAt==null) shadow.modelStartedAt=Novel.now();
                return null;
            });
            Review formalReview=formalReview(novelId,artifactId,versionId);
            Review report=professionalPolicy.normalize(reviews.aggregateShadow(modelCall.get(),candidate),
                    candidate,request,formalReview);
            if (ContinuityShadowService.CHECKER.equals(spec.checker())) report=continuityPolicy.normalize(report);
            Review finalReport=report;
            repository.update(novelId,n->{
                ShadowReview shadow=findShadow(n,pending.reportId()); AgentRun run=findRun(n,pending.runId());
                shadow.review=finalReport; shadow.status=ShadowReviewStatus.SUCCEEDED; shadow.finishedAt=Novel.now();
                finish(run,AgentRunStatus.SUCCEEDED,artifactId,versionId,null); return null;
            });
        } catch (Exception e) { fail(spec,novelId,taskId,pending,e); }
    }

    private Review formalReview(String novelId,String artifactId,String versionId) {
        Novel novel=repository.get(novelId);
        return novel.artifacts.stream().filter(artifact->artifactId.equals(artifact.id))
                .flatMap(artifact->artifact.versions.stream()).filter(version->versionId.equals(version.id))
                .map(version->version.review).findFirst().orElse(null);
    }

    private void fail(Spec spec,String novelId,String taskId,Pending pending,Exception cause) {
        log.warn("Shadow review failed: checker={} task={} exception={}",
                spec.checker(),taskId,cause.getClass().getSimpleName());
        try {
            repository.update(novelId,n->{
                ShadowReview shadow=findShadow(n,pending.reportId()); AgentRun run=findRun(n,pending.runId());
                String error=cause instanceof Problem?cause.getMessage():spec.failureMessage();
                shadow.error=error; shadow.status=ShadowReviewStatus.FAILED; shadow.finishedAt=Novel.now();
                finish(run,AgentRunStatus.FAILED,null,null,error); return null;
            });
        } catch (Exception persistenceFailure) {
            log.warn("Shadow review failure could not be persisted: checker={} task={} exception={}",
                    spec.checker(),taskId,persistenceFailure.getClass().getSimpleName());
        }
    }

    private Pending prepare(Spec spec,Novel novel,String taskId,String sourceSnapshotId,String artifactId,String versionId,
                            List<String> upstreamAgentRunIds) {
        if (novel.shadowReviews==null) novel.shadowReviews=new ArrayList<>();
        if (novel.agentRuns==null) novel.agentRuns=new ArrayList<>();
        boolean exists=novel.shadowReviews.stream().anyMatch(item->spec.checker().equals(item.checker)
                && versionId.equals(item.versionId));
        if (exists) return null;
        require(novel.artifacts.stream().anyMatch(a->artifactId.equals(a.id)
                        && a.versions.stream().anyMatch(v->versionId.equals(v.id))),
                "专业影子检查的候选版本不存在");
        AgentRun run=new AgentRun(); run.taskId=taskId; run.sourceSnapshotId=sourceSnapshotId;
        run.role=spec.role().name(); run.operation=spec.operation(); run.inputVersionId=versionId;
        run.upstreamAgentRunIds=new ArrayList<>(upstreamAgentRunIds);
        ShadowReview report=new ShadowReview(); report.taskId=taskId; report.agentRunId=run.id;
        report.sourceSnapshotId=sourceSnapshotId; report.artifactId=artifactId; report.versionId=versionId;
        report.checker=spec.checker(); report.policyVersion=spec.policyVersion();
        novel.agentRuns.add(run); novel.shadowReviews.add(report);
        novel.tasks.stream().filter(task->taskId.equals(task.id)).findFirst()
                .ifPresent(task->task.agentRunIds.add(run.id));
        return new Pending(report.id,run.id);
    }

    private ShadowReview findShadow(Novel novel,String id) {
        require(novel.shadowReviews!=null,"专业影子检查记录不存在");
        return novel.shadowReviews.stream().filter(item->id.equals(item.id)).findFirst()
                .orElseThrow(()->new Problem(409,"专业影子检查记录不存在"));
    }

    private AgentRun findRun(Novel novel,String id) {
        require(novel.agentRuns!=null,"专业检查 Agent 调用记录不存在");
        return novel.agentRuns.stream().filter(item->id.equals(item.id)).findFirst()
                .orElseThrow(()->new Problem(409,"专业检查 Agent 调用记录不存在"));
    }

    private void finish(AgentRun run,AgentRunStatus status,String artifactId,String versionId,String error) {
        if (run.status!=AgentRunStatus.RUNNING) return;
        run.status=status; run.resultArtifactId=artifactId; run.resultVersionId=versionId;
        run.error=error; run.finishedAt=Novel.now();
    }

    @PreDestroy public void close() { executor.shutdownNow(); }
}
