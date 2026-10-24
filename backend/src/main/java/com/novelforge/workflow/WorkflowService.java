package com.novelforge.workflow;

import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.novel.WordCounter;
import com.novelforge.generation.ModelGateway;
import com.novelforge.generation.PlanNarrativeRenderer;
import com.novelforge.generation.StateExtractionPolicy;
import com.novelforge.outline.OutlineSpec;
import com.novelforge.outline.OutlineStructureService;
import com.novelforge.revision.ImpactAnalyzer;
import org.springframework.stereotype.Service;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import static com.novelforge.shared.Problem.require;

@Service
public class WorkflowService {
    private final NovelRepository repository;
    private final WorkflowRules rules;
    private final WordCounter counter;
    private final ImpactAnalyzer impact;
    private final OutlineStructureService outlineStructures;
    private final StateExtractionPolicy stateExtractions;
    public WorkflowService(NovelRepository repository, WorkflowRules rules, WordCounter counter, ImpactAnalyzer impact,
                           OutlineStructureService outlineStructures,StateExtractionPolicy stateExtractions) {
        this.repository=repository; this.rules=rules; this.counter=counter; this.impact=impact;
        this.outlineStructures=outlineStructures;
        this.stateExtractions=stateExtractions;
    }
    public Novel create(String title, String synopsis, long target, String requirements) {
        Novel n = new Novel(); n.title=title.strip(); n.synopsis=synopsis.strip();
        n.targetWords=target; n.approvedMaxWords=counter.defaultMax(target);
        n.autoStyleEnabled=true;
        n.requirements=requirements == null ? "" : requirements;
        repository.insert(n); return n;
    }
    public Novel autoStyle(String id,boolean enabled,long revision) {
        return repository.update(id,n->{
            expected(n,revision); idle(n);
            n.autoStyleEnabled=enabled;
            // This is an execution preference, not a story-content change. Do not advance the
            // business revision or invalidate already completed content reviews.
            return n;
        });
    }
    public Novel confirm(String id, String artifactId, String versionId, long revision, String overrideReason) {
        return repository.update(id, n -> {
            Artifact a = rules.artifact(n, artifactId);
            if (a.clean() && versionId.equals(a.approvedVersionId)) return n; // safe replay
            expected(n, revision); idle(n);
            require(rules.pending(n) != null && rules.pending(n).id.equals(a.id), "请先处理上游待确认内容");
            require(!a.needsRevision, "内容已受修改影响，必须先修订生成新版本，不能直接重新确认旧版本");
            Version v = a.latest();
            require(v.id.equals(versionId), "仅能确认最新候选版本");
            require(v.draftIssues == null || v.draftIssues.isEmpty(), "当前草稿尚未完整，请先补全摘要或其他缺失项并重新检查，不能直接确认");
            rules.validateVersion(n, a, v);
            require(rules.reviewCurrent(n,v), "请先针对当前版本与依据执行检查");
            require(v.review.passed() || (overrideReason != null && overrideReason.strip().length() >= 5), "检查存在问题；请修订，或明确填写人工复核接受理由（至少5字符）");
            if (a.kind == Kind.CHAPTER) {
                require(!v.stateExtractionRequired || stateExtractions.current(v),
                        "当前正文的独立状态提取尚未完成或已经过期；正文草稿仍然保留，请只重试状态提取后再确认");
                long projected = counter.approvedWords(n) - (a.approved() == null ? 0 : counter.count(a.approved().content)) + counter.count(v.content);
                require(projected <= n.approvedMaxWords, "确认后将超过已批准字数上限，请先修订正文或批准扩充预算");
            }
            boolean replacesApproved=a.approvedVersionId!=null&&!a.approvedVersionId.equals(v.id);
            a.approvedVersionId=v.id; a.needsRevision=false;
            if (replacesApproved)
                impact.invalidateFollowing(n,a,impactReason(v));
            n.revision++;
            n.approvals.add(new Approval(a.id, v.id, n.revision, overrideReason, Novel.now()));
            return n;
        });
    }
    public Novel dismissCandidate(String id,String artifactId,String versionId,long revision) {
        return repository.update(id,n->{
            expected(n,revision); idle(n);
            Artifact artifact=rules.artifact(n,artifactId);
            Version latest=artifact.latest();
            require(latest!=null && latest.id.equals(versionId),"只能放弃当前最新候选版本");
            require(!versionId.equals(artifact.approvedVersionId),"已确认版本不能放弃");
            latest.dismissed=true; latest.dismissedAt=Novel.now();
            artifact.needsRevision=latest.previousNeedsRevision;
            n.revision++;
            return n;
        });
    }

    /** Starts an explicit history rewrite without deleting any approved version. */
    public Novel beginChapterRewrite(String id,int chapterNumber,String instructions,long revision) {
        return repository.update(id,n->{
            expected(n,revision); idle(n);
            require(rules.pending(n)==null,"请先处理当前待确认或待修订内容");
            require(chapterNumber>0,"起始章节必须大于零");
            require(instructions!=null&&!instructions.isBlank(),"请说明这次重新创作的目标");
            Artifact chapter=n.artifacts.stream().filter(a->a.kind==Kind.CHAPTER&&a.chapterNumber==chapterNumber&&a.clean())
                    .findFirst().orElseThrow(()->new com.novelforge.shared.Problem(409,"只能从已确认章节开始重新创作"));
            Artifact sourcePlan=n.artifacts.stream().filter(a->a.kind==Kind.PLAN&&a.clean()&&a.approved().plan.startChapter<=chapterNumber
                            &&a.approved().plan.endChapter>=chapterNumber)
                    .findFirst().orElseThrow(()->new com.novelforge.shared.Problem(409,"当前章节没有可修订的已确认规划"));

            LinkedHashSet<String> affected=new LinkedHashSet<>();
            for(Artifact artifact:n.artifacts) {
                boolean plan=artifact.kind==Kind.PLAN&&artifact.batchNumber>=sourcePlan.batchNumber&&artifact.approvedVersionId!=null;
                boolean written=artifact.kind==Kind.CHAPTER&&artifact.chapterNumber>=chapterNumber&&artifact.approvedVersionId!=null;
                if(plan||written) { artifact.needsRevision=true; affected.add(artifact.id); }
            }
            require(affected.contains(chapter.id)&&affected.contains(sourcePlan.id),"无法建立章节重写范围");
            Change change=new Change(); change.sourceArtifactId=sourcePlan.id;
            change.reason="从第"+chapterNumber+"章重新创作："+instructions.strip();
            change.affectedArtifactIds.addAll(affected); n.changes.add(change);
            n.status="WRITING"; n.revision++;
            return n;
        });
    }
    public record Edit(String baseVersionId, String title, String content, String summary, List<Fact> facts, Plan plan, String reason, long revision) {}
    public Novel edit(String id, String artifactId, Edit edit) {
        return repository.update(id, n -> {
            expected(n, edit.revision()); idle(n);
            Artifact a = rules.artifact(n, artifactId);
            require(a.latest().id.equals(edit.baseVersionId()), "基础版本已过期，请刷新后编辑");
            if (a.kind==Kind.OUTLINE && a.latest().outlineSpec!=null)
                require(java.util.Objects.equals(a.latest().content,edit.content()),
                        "结构化大纲暂不能直接修改展示正文；请把修改要求交给大纲 Agent，以同步更新结构和作者可读版本");
            String content=a.kind==Kind.PLAN&&edit.plan()!=null?PlanNarrativeRenderer.render(edit.plan()):edit.content();
            String summary=a.kind==Kind.PLAN&&edit.plan()!=null?PlanNarrativeRenderer.summary(edit.plan()):edit.summary();
            require(impact.changed(a.latest(),edit.title(),content,summary,edit.facts(),edit.plan()),
                    "内容没有发生变化，无需创建新版本或重新检查");
            Version v = new Version(); v.baseVersionId=a.latest().id; v.title=edit.title(); v.content=content;
            v.summary=summary; v.facts=edit.facts(); v.plan=edit.plan(); v.source="USER"; v.basedOnRevision=n.revision;
            v.pendingImpactReason=edit.reason();
            if(a.kind==Kind.CHAPTER) {
                v.facts=List.of();
                v.stateExtractionRequired=true; v.stateExtractionStatus=StateExtractionStatus.PENDING;
                Version base=a.latest();
                if(Objects.equals(base.content,v.content) && stateExtractions.current(base)) {
                    v.facts=List.copyOf(base.facts);
                    v.stateExtractionStatus=StateExtractionStatus.SUCCEEDED;
                    v.stateExtractionPolicyVersion=base.stateExtractionPolicyVersion;
                    v.stateExtractionSourceHash=base.stateExtractionSourceHash;
                    v.stateExtractionAgentRunId=base.stateExtractionAgentRunId;
                    v.stateExtractionError=base.stateExtractionError;
                    v.stateEvidence=List.copyOf(base.stateEvidence);
                    v.stateEntities=List.copyOf(base.stateEntities);
                    v.stateRelations=List.copyOf(base.stateRelations);
                }
                if(Objects.equals(base.content,v.content)) {
                    v.styleReview=base.styleReview;
                    v.styleReviewPolicyVersion=base.styleReviewPolicyVersion;
                    v.stylePolishRound=base.stylePolishRound;
                }
            }
            v.outlineSpec=a.latest().outlineSpec; v.outlineSpecHash=a.latest().outlineSpecHash;
            rules.validateVersion(n, a, v);
            a.versions.add(v); a.needsRevision=false; n.revision++;
            return n;
        });
    }
    public record OutlineEdit(String baseVersionId,String title,String summary,OutlineSpec outlineSpec,
                              String reason,long revision) {}
    public Novel editOutline(String id,String artifactId,OutlineEdit edit) {
        return repository.update(id,n->{
            expected(n,edit.revision()); idle(n);
            Artifact artifact=rules.artifact(n,artifactId);
            require(artifact.kind==Kind.OUTLINE,"结构化编辑只适用于全书大纲");
            Version base=artifact.latest();
            require(base!=null && base.id.equals(edit.baseVersionId()),"基础版本已过期，请刷新后编辑");
            require(base.outlineSpec!=null,"当前是旧版自然语言大纲，请使用 Agent 生成结构化修订版本后再编辑");
            require(edit.outlineSpec()!=null,"结构化大纲不能为空");
            String rendered=outlineStructures.render(edit.outlineSpec());
            String hash=outlineStructures.hash(edit.outlineSpec());
            require(!Objects.equals(base.title,edit.title()) || !Objects.equals(base.summary,edit.summary())
                            || !Objects.equals(base.content,rendered),
                    "大纲没有发生变化，无需创建新版本或重新检查");

            Version version=new Version();
            version.baseVersionId=base.id; version.title=edit.title(); version.content=rendered;
            version.summary=edit.summary(); version.facts=base.facts==null?List.of():List.copyOf(base.facts);
            version.plan=base.plan; version.outlineSpec=edit.outlineSpec(); version.outlineSpecHash=hash;
            version.source="USER"; version.basedOnRevision=n.revision;
            version.pendingImpactReason=edit.reason();
            rules.validateVersion(n,artifact,version);
            ModelGateway.Generated candidate=new ModelGateway.Generated(version.title,version.content,version.summary,
                    version.facts,version.plan,version.outlineSpec,List.of());
            version.review=outlineStructures.validate(n,candidate,version);
            version.reviewRevision=n.revision+1;
            version.reviewPolicyVersion=OutlineStructureService.VERSION;

            artifact.versions.add(version); artifact.needsRevision=false; n.revision++;
            return n;
        });
    }

    private String impactReason(Version version) {
        return version.pendingImpactReason==null||version.pendingImpactReason.isBlank()
                ?"已确认上游内容修订，需要同步复核后续内容"
                :version.pendingImpactReason;
    }
    public Novel budget(String id, Long requestedTarget, long max, String reason, long revision) {
        return repository.update(id, n -> {
            expected(n, revision); idle(n);
            long target=requestedTarget==null ? n.targetWords : requestedTarget;
            require(target >= 10 && target <= 50_000_000, "目标字数必须在10字到5000万字之间");
            require(max > target && max >= counter.approvedWords(n) && max <= 100_000_000,
                    "字数上限必须大于目标字数、不小于已确认字数，且最多一亿字");
            require(reason != null && !reason.isBlank(), "预算调整必须填写理由");
            require(target != n.targetWords || max != n.approvedMaxWords, "目标字数和字数上限都没有发生变化");
            n.budgetChanges.add(new BudgetChange(n.targetWords, target, n.approvedMaxWords, max, reason, Novel.now()));
            n.targetWords=target;
            n.approvedMaxWords=max; n.revision++;
            return n;
        });
    }
    /** Backward-compatible service entry point for callers that only adjust the upper limit. */
    public Novel budget(String id, long max, String reason, long revision) {
        return budget(id, null, max, reason, revision);
    }
    public Novel finish(String id, String checkId, long revision, boolean acknowledge) {
        return repository.update(id, n -> {
            expected(n, revision); idle(n); rules.validateCompletionBudget(n);
            require(rules.nextAction(n).equals("COMPLETE"), "尚未完成最终批次规划");
            Completion c = n.completionChecks.stream().filter(x -> x.id().equals(checkId)).findFirst().orElse(null);
            require(c != null && c.revision() == n.revision, "完结检查缺失或已过期");
            Review r = c.review();
            require(r.passed() && r.mainlineResolved() && r.endingClear() && r.foreshadowingResolved(), "完结检查尚未通过，请修订后重查");
            require(acknowledge, "必须由用户明确确认主线、结局和伏笔完成");
            n.status="COMPLETED"; n.revision++; return n;
        });
    }
    public void expected(Novel n, long revision) { require(n.revision == revision, "页面版本已过期，请刷新后重试"); }
    public void idle(Novel n) { require(n.tasks.stream().noneMatch(t -> t.status == TaskStatus.QUEUED || t.status == TaskStatus.RUNNING), "当前有任务执行中，请等待或取消任务"); }
}
