package com.novelforge.workflow;

import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.novel.WordCounter;
import com.novelforge.generation.ModelGateway;
import com.novelforge.outline.OutlineSpec;
import com.novelforge.outline.OutlineStructureService;
import com.novelforge.revision.ImpactAnalyzer;
import org.springframework.stereotype.Service;
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
    public WorkflowService(NovelRepository repository, WorkflowRules rules, WordCounter counter, ImpactAnalyzer impact,
                           OutlineStructureService outlineStructures) {
        this.repository=repository; this.rules=rules; this.counter=counter; this.impact=impact;
        this.outlineStructures=outlineStructures;
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
                long projected = counter.approvedWords(n) - (a.approved() == null ? 0 : counter.count(a.approved().content)) + counter.count(v.content);
                require(projected <= n.approvedMaxWords, "确认后将超过已批准字数上限，请先修订正文或批准扩充预算");
            }
            a.approvedVersionId=v.id; a.needsRevision=false; n.revision++;
            n.approvals.add(new Approval(a.id, v.id, n.revision, overrideReason, Novel.now()));
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
            require(impact.changed(a.latest(),edit.title(),edit.content(),edit.summary(),edit.facts(),edit.plan()),
                    "内容没有发生变化，无需创建新版本或重新检查");
            Version v = new Version(); v.baseVersionId=a.latest().id; v.title=edit.title(); v.content=edit.content();
            v.summary=edit.summary(); v.facts=edit.facts(); v.plan=edit.plan(); v.source="USER"; v.basedOnRevision=n.revision;
            v.outlineSpec=a.latest().outlineSpec; v.outlineSpecHash=a.latest().outlineSpecHash;
            rules.validateVersion(n, a, v);
            impact.invalidateFollowing(n, a, edit.reason());
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
                            || !Objects.equals(base.outlineSpecHash,hash),
                    "大纲没有发生变化，无需创建新版本或重新检查");

            Version version=new Version();
            version.baseVersionId=base.id; version.title=edit.title(); version.content=rendered;
            version.summary=edit.summary(); version.facts=base.facts==null?List.of():List.copyOf(base.facts);
            version.plan=base.plan; version.outlineSpec=edit.outlineSpec(); version.outlineSpecHash=hash;
            version.source="USER"; version.basedOnRevision=n.revision;
            rules.validateVersion(n,artifact,version);
            ModelGateway.Generated candidate=new ModelGateway.Generated(version.title,version.content,version.summary,
                    version.facts,version.plan,version.outlineSpec,List.of());
            version.review=outlineStructures.validate(n,candidate,version);
            version.reviewRevision=n.revision+1;
            version.reviewPolicyVersion=OutlineStructureService.VERSION;

            impact.invalidateFollowing(n,artifact,edit.reason());
            artifact.versions.add(version); artifact.needsRevision=false; n.revision++;
            return n;
        });
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
