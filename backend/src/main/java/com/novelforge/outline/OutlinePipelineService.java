package com.novelforge.outline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelforge.generation.ContextAssembler;
import com.novelforge.generation.ModelGateway;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.OutlineFoundationDraft;
import com.novelforge.novel.Novel.OutlinePipelineStatus;
import com.novelforge.novel.Novel.OutlinePipelineWorkspace;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import com.novelforge.shared.Problem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static com.novelforge.shared.Problem.require;

/** Persisted, bounded workspace for the outline parent task. It is not a user-confirmable artifact. */
@Service
public class OutlinePipelineService {
    public static final String VERSION="2026-09-28-outline-v1";
    public static final String FOUNDATION="FOUNDATION";
    public static final String ARCHITECT="ARCHITECT";
    public static final String CONTINUITY="CONTINUITY_REVIEW";
    public static final String PLOT="PLOT_REVIEW";

    private final NovelRepository repository;
    private final ObjectMapper mapper;

    public OutlinePipelineService(NovelRepository repository,ObjectMapper mapper) {
        this.repository=repository;
        this.mapper=mapper;
    }

    public String begin(String novelId,String taskId,String sourceSnapshotId,int maxModelCalls,
                        String artifactId,String versionId,String resumedFromWorkspaceId) {
        require(maxModelCalls>=2 && maxModelCalls<=4,"大纲流水线模型调用额度必须为2至4次");
        return repository.update(novelId,n->{
            ensure(n);
            OutlinePipelineWorkspace existing=n.outlinePipelines.stream()
                    .filter(item->taskId.equals(item.taskId)).findFirst().orElse(null);
            if (existing!=null) return existing.id;
            OutlinePipelineWorkspace workspace=new OutlinePipelineWorkspace();
            workspace.taskId=taskId; workspace.sourceSnapshotId=sourceSnapshotId;
            workspace.maxModelCalls=maxModelCalls; workspace.artifactId=artifactId; workspace.versionId=versionId;
            workspace.resumedFromWorkspaceId=resumedFromWorkspaceId;
            n.outlinePipelines.add(workspace);
            return workspace.id;
        });
    }

    public void startStep(String novelId,String workspaceId,String step,String runId) {
        repository.update(novelId,n->{
            OutlinePipelineWorkspace workspace=find(n,workspaceId);
            require(workspace.status==OutlinePipelineStatus.RUNNING,"大纲流水线已经停止");
            require(workspace.modelCallsUsed<workspace.maxModelCalls,"大纲流水线模型调用额度已经用完");
            workspace.modelCallsUsed++;
            workspace.currentStep=step;
            switch (step) {
                case FOUNDATION -> workspace.foundationRunId=runId;
                case ARCHITECT -> workspace.architectRunId=runId;
                case CONTINUITY -> workspace.continuityRunId=runId;
                case PLOT -> workspace.plotRunId=runId;
                default -> throw new Problem(400,"未知的大纲流水线步骤");
            }
            return null;
        });
    }

    public OutlineFoundationDraft saveFoundation(String novelId,String workspaceId,ModelGateway.Generated generated) {
        require(generated!=null && generated.content()!=null && !generated.content().isBlank(),"人物世界参谋没有返回有效材料");
        OutlineFoundationDraft draft=new OutlineFoundationDraft();
        draft.title=generated.title(); draft.content=generated.content(); draft.summary=generated.summary();
        draft.facts=new ArrayList<>(generated.facts());
        repository.update(novelId,n->{
            OutlinePipelineWorkspace workspace=find(n,workspaceId);
            workspace.foundation=draft; workspace.currentStep="FOUNDATION_READY";
            return null;
        });
        return draft;
    }

    public ModelGateway.Request withFoundation(ModelGateway.Request request,OutlineFoundationDraft foundation) {
        require(request!=null && request.context()!=null && foundation!=null,"大纲主笔缺少冻结输入或参谋材料");
        try {
            ObjectNode root=(ObjectNode)mapper.readTree(request.context().json());
            root.set("outlineFoundation",mapper.valueToTree(foundation));
            root.put("outlineFoundationAuthority","CREATIVE_ASSUMPTION_NOT_CONFIRMED");
            ContextAssembler.Context enriched=new ContextAssembler.Context(mapper.writeValueAsString(root),
                    request.context().sourceVersions(),request.context().chapterNumber(),request.context().batchNumber());
            return new ModelGateway.Request(request.action(),request.novel(),request.target(),enriched,request.instructions());
        } catch (Exception e) {
            throw new Problem(500,"大纲参谋材料无法合并到冻结上下文；尚未调用故事架构 Agent");
        }
    }

    public void saveCandidate(String novelId,String workspaceId,String artifactId,String versionId) {
        repository.update(novelId,n->{
            OutlinePipelineWorkspace workspace=find(n,workspaceId);
            workspace.artifactId=artifactId; workspace.versionId=versionId; workspace.currentStep="CANDIDATE_SAVED";
            return null;
        });
    }

    public void saveAudit(String novelId,String workspaceId,String step,Review review) {
        require(review!=null,"大纲专业检查没有返回报告");
        repository.update(novelId,n->{
            OutlinePipelineWorkspace workspace=find(n,workspaceId);
            if (CONTINUITY.equals(step)) workspace.continuityReview=review;
            else if (PLOT.equals(step)) workspace.plotReview=review;
            else throw new Problem(400,"未知的大纲检查步骤");
            workspace.currentStep=step+"_READY";
            return null;
        });
    }

    public void saveReviewScope(String novelId,String workspaceId,OutlineImpactAnalyzer.ReviewScope scope) {
        require(scope!=null,"大纲复查范围不能为空");
        repository.update(novelId,n->{
            OutlinePipelineWorkspace workspace=find(n,workspaceId);
            workspace.changedSections=new ArrayList<>(scope.changedSections());
            workspace.continuityReviewRequired=scope.continuityRequired();
            workspace.plotReviewRequired=scope.plotRequired();
            return null;
        });
    }

    public void reuseAudit(String novelId,String workspaceId,String step,Review review,String sourceWorkspaceId) {
        require(review!=null && sourceWorkspaceId!=null,"复用专业检查需要来源报告");
        repository.update(novelId,n->{
            OutlinePipelineWorkspace workspace=find(n,workspaceId);
            if (CONTINUITY.equals(step)) {
                workspace.continuityReview=review;
                workspace.continuityReviewReusedFromWorkspaceId=sourceWorkspaceId;
            } else if (PLOT.equals(step)) {
                workspace.plotReview=review;
                workspace.plotReviewReusedFromWorkspaceId=sourceWorkspaceId;
            } else throw new Problem(400,"未知的大纲检查步骤");
            workspace.currentStep=step+"_REUSED";
            return null;
        });
    }

    public void saveDeterministicReview(String novelId,String workspaceId,Review review) {
        require(review!=null,"结构化大纲确定性检查没有返回报告");
        repository.update(novelId,n->{
            OutlinePipelineWorkspace workspace=find(n,workspaceId);
            workspace.deterministicReview=review;
            workspace.currentStep="DETERMINISTIC_REVIEW_READY";
            return null;
        });
    }

    public Review merge(Review continuity,Review plot) {
        return merge(null,continuity,plot);
    }

    public Review merge(Review deterministic,Review continuity,Review plot) {
        require(continuity!=null && plot!=null,"大纲必须完成连贯性与情节伏笔两类检查");
        LinkedHashMap<String,ReviewIssue> unique=new LinkedHashMap<>();
        List<Review> reports=new ArrayList<>();
        if (deterministic!=null) reports.add(deterministic);
        reports.add(continuity); reports.add(plot);
        for (Review review:reports) for (ReviewIssue issue:review.issueDetails()) {
            String key=(issue.issueId()==null?"":issue.issueId()+"|")+issue.location()+"|"+issue.problem()+"|"+issue.evidence();
            unique.putIfAbsent(key,issue);
        }
        List<ReviewIssue> details=new ArrayList<>(unique.values());
        boolean passed=details.stream().noneMatch(issue->"必须修正".equals(issue.severity()));
        return new Review(passed,details.stream().map(ReviewIssue::text).toList(),plot.mainlineResolved(),
                plot.endingClear(),plot.foreshadowingResolved(),details);
    }

    public void complete(String novelId,String workspaceId,Review combined) {
        repository.update(novelId,n->{
            OutlinePipelineWorkspace workspace=find(n,workspaceId);
            workspace.combinedReview=combined; workspace.status=OutlinePipelineStatus.SUCCEEDED;
            workspace.currentStep="COMPLETED"; workspace.finishedAt=Novel.now(); workspace.error=null;
            return null;
        });
    }

    public void needsInput(String novelId,String workspaceId,String error) {
        finish(novelId,workspaceId,OutlinePipelineStatus.NEEDS_INPUT,error);
    }

    public void stop(String novelId,String workspaceId,OutlinePipelineStatus status,String error) {
        require(status==OutlinePipelineStatus.CANCELLED || status==OutlinePipelineStatus.STALE
                        || status==OutlinePipelineStatus.INTERRUPTED,
                "大纲流水线停止状态不合法");
        finish(novelId,workspaceId,status,error);
    }

    public void fail(String novelId,String workspaceId,String error) {
        finish(novelId,workspaceId,OutlinePipelineStatus.FAILED,error);
    }

    private void finish(String novelId,String workspaceId,OutlinePipelineStatus status,String error) {
        if (workspaceId==null) return;
        try {
            repository.update(novelId,n->{
                OutlinePipelineWorkspace workspace=find(n,workspaceId);
                if (workspace.status==OutlinePipelineStatus.RUNNING) {
                    workspace.status=status; workspace.error=error;
                    workspace.finishedAt=Novel.now();
                }
                return null;
            });
        } catch (Exception ignored) { }
    }

    public List<OutlinePipelineWorkspace> list(String novelId) {
        Novel novel=repository.get(novelId); ensure(novel); return List.copyOf(novel.outlinePipelines);
    }

    public OutlinePipelineWorkspace find(String novelId,String workspaceId) {
        return find(repository.get(novelId),workspaceId);
    }

    public OutlinePipelineWorkspace latestForVersion(Novel novel,String versionId) {
        ensure(novel);
        return novel.outlinePipelines.stream().filter(item->versionId!=null && versionId.equals(item.versionId))
                .reduce((first,second)->second).orElse(null);
    }

    private OutlinePipelineWorkspace find(Novel novel,String id) {
        ensure(novel);
        return novel.outlinePipelines.stream().filter(item->id.equals(item.id)).findFirst()
                .orElseThrow(()->new Problem(404,"大纲流水线工作区不存在"));
    }

    public void ensure(Novel novel) {
        if (novel.outlinePipelines==null) novel.outlinePipelines=new ArrayList<>();
    }
}
