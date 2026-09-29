package com.novelforge.workflow;

import com.novelforge.generation.ReviewPolicy;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.novel.CharacterFactPolicy;
import com.novelforge.novel.VisibleContentPolicy;
import com.novelforge.novel.WordCounter;
import com.novelforge.shared.Problem;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import static com.novelforge.shared.Problem.require;

@Component
public class WorkflowRules {
    private final WordCounter counter;
    public WorkflowRules(WordCounter counter) { this.counter = counter; }
    public boolean reviewCurrent(Novel n, Version version) {
        return version.review != null && version.reviewRevision == n.revision
                && ReviewPolicy.VERSION.equals(version.reviewPolicyVersion);
    }
    public Artifact artifact(Novel n, String id) {
        return n.artifacts.stream().filter(a -> a.id.equals(id)).findFirst()
                .orElseThrow(() -> new Problem(404, "内容不属于该小说或不存在"));
    }
    public Artifact pending(Novel n) { return n.artifacts.stream().filter(a -> !a.clean()).findFirst().orElse(null); }
    public int nextChapter(Novel n) {
        return n.artifacts.stream().filter(a -> a.kind == Kind.CHAPTER && a.clean())
                .mapToInt(a -> a.chapterNumber).max().orElse(0) + 1;
    }
    public List<Artifact> plans(Novel n) {
        return n.artifacts.stream().filter(a -> a.kind == Kind.PLAN && a.clean())
                .sorted(Comparator.comparingInt(a -> a.batchNumber)).toList();
    }
    public Artifact latestPlan(Novel n) {
        var plans = plans(n); return plans.isEmpty() ? null : plans.getLast();
    }
    public String nextAction(Novel n) {
        if (n.status.equals("COMPLETED")) return "COMPLETED";
        Artifact pending = pending(n);
        if (pending != null) return pending.needsRevision ? "REPAIR" : "CONFIRM";
        if (n.artifacts.stream().noneMatch(a -> a.kind == Kind.OUTLINE && a.clean())) return "OUTLINE";
        if (n.artifacts.stream().noneMatch(a -> a.kind == Kind.CHARACTERS && a.clean())) return "CHARACTERS";
        Artifact last = latestPlan(n);
        if (last == null) return "PLAN";
        int next = nextChapter(n);
        Plan p = last.approved().plan;
        if (p.finalBatch && next > p.endChapter) return "COMPLETE";
        if (counter.approvedWords(n) >= n.approvedMaxWords) return "BUDGET";
        if (!p.finalBatch && next > p.prepareNextAfterChapter) return "PLAN";
        return "CHAPTER";
    }
    public void validateAction(Novel n, Action action, String artifactId) {
        require(!n.status.equals("COMPLETED") || action == Action.REWRITE || action == Action.STYLE_REVIEW,
                "小说已完结；请先通过修改请求开启修订");
        if (action == Action.STYLE_REVIEW) {
            Artifact a=artifact(n,artifactId);
            require(a.kind==Kind.CHAPTER && a.latest()!=null,"文风检查只适用于已有章节正文");
            return;
        }
        if (action == Action.REWRITE || action == Action.REVIEW) {
            Artifact a = artifact(n, artifactId);
            Artifact first = pending(n);
            require(first == null || first.id.equals(a.id) || (action == Action.REWRITE && n.artifacts.indexOf(a) < n.artifacts.indexOf(first)), "请先处理更早的待确认或待修订内容");
            if (action == Action.REVIEW) {
                require(!a.clean(), "已确认内容无需重新检查，请先创建修改版本");
                Version latest=a.latest();
                require(!reviewCurrent(n,latest),
                        "当前内容和检查依据均未发生变化，已有检查结果仍然有效；请先修改内容，不要重复调用模型");
            }
            return;
        }
        require(nextAction(n).equals(action.name()), "当前应当“" + actionName(nextAction(n)) + "”，不能执行“" + actionName(action.name()) + "”");
        if (action == Action.CHAPTER) {
            int chapter = nextChapter(n);
            require(plans(n).stream().anyMatch(a -> a.approved().plan.startChapter <= chapter && a.approved().plan.endChapter >= chapter), "当前章节没有已确认规划");
        }
        if (action == Action.COMPLETE) validateCompletionBudget(n);
    }
    public void validateCompletionBudget(Novel n) {
        long words = counter.approvedWords(n);
        require(words <= n.approvedMaxWords, "正文超过已批准上限，请修订或明确增加预算");
        require(pending(n) == null, "仍有未确认或待修订内容");
    }
    public void validateVersion(Novel n, Artifact a, Version v) {
        require(v.title != null && !v.title.isBlank() && v.title.length() <= 300, "内容标题必须为 1–300 字符");
        require(v.content != null && !v.content.isBlank() && v.content.length() <= 250000, "内容不能为空或超过单份内容限制");
        require(v.summary != null && !v.summary.isBlank(), "请提供内容摘要，供后续连续创作使用");
        require(v.summary.length() <= 5000 && v.facts != null && v.facts.size() <= 200, "摘要或档案增量过大");
        Version base=baseVersion(a,v);
        if (base==null || !v.title.equals(base.title)) VisibleContentPolicy.validate("标题",v.title);
        if (base==null || !v.content.equals(base.content)) VisibleContentPolicy.validate("正文",v.content);
        if (base==null || !v.summary.equals(base.summary)) VisibleContentPolicy.validate("摘要",v.summary);
        var keys=new HashSet<String>();
        for (Fact f : v.facts) {
            require(f != null && f.key() != null && !f.key().isBlank() && f.detail() != null && !f.detail().isBlank(), "档案条目需要稳定的内部编号和中文内容");
            require(keys.add(f.key()), "同一份档案中不能出现重复的内部编号");
            require(List.of("WORLD", "CHARACTER", "TIMELINE", "EVENT", "FORESHADOW").contains(f.type()), "未知档案类型");
            require(List.of("ACTIVE", "OPEN", "RESOLVED").contains(f.state()), "未知档案状态");
            if (a.kind == Kind.CHARACTERS) {
                boolean unchangedLegacyFact=unchangedFromBase(a,v,f);
                if (!unchangedLegacyFact) CharacterFactPolicy.validate(f);
            }
        }
        if (a.kind != Kind.PLAN) return;
        Plan p = v.plan;
        require(p != null, "章节规划缺少结构化批次信息");
        require(p.startChapter > 0 && p.endChapter >= p.startChapter && p.endChapter <= 100000, "章节范围不合法");
        require(p.endChapter - p.startChapter < 500, "单批最多 500 章；可拆为多个批次，不限制全书批次数");
        require(p.finalBatch || (p.prepareNextAfterChapter >= p.startChapter && p.prepareNextAfterChapter < p.endChapter), "非最终批次必须在结束前触发下一批规划");
        require(p.handoff != null && !p.handoff.isBlank() && p.assumptions != null && p.triggerReason != null && !p.triggerReason.isBlank(), "规划必须说明衔接、假设和提前规划的剧情节点");
        require(p.chapters != null && p.chapters.size() == p.endChapter - p.startChapter + 1, "章节安排必须覆盖完整范围");
        for (int i = 0; i < p.chapters.size(); i++) {
            ChapterBeat beat = p.chapters.get(i);
            require(beat != null && beat.number() == p.startChapter + i && beat.title() != null && !beat.title().isBlank() && beat.purpose() != null && !beat.purpose().isBlank(), "章节安排必须连续且有标题、剧情目的");
            require(beat.targetWords()==null || (beat.targetWords()>=100 && beat.targetWords()<=10000), "章节建议字数必须为100至10000；它只用于写作引导，不是确认门禁");
            require(beat.sceneBeats()!=null && beat.sceneBeats().size()<=4
                    && beat.sceneBeats().stream().noneMatch(item->item==null||item.isBlank()), "每章最多四个非空场景节点");
        }
        Artifact previous = n.artifacts.stream().filter(x -> x.kind == Kind.PLAN && x.batchNumber == a.batchNumber - 1).findFirst().orElse(null);
        int start = previous == null ? 1 : previous.approved().plan.endChapter + 1;
        require(p.startChapter == start, "批次必须接续前一批，不得重叠或留空");
        if (a.approved() != null) {
            Plan old = a.approved().plan;
            int written = n.artifacts.stream().filter(x -> x.kind == Kind.CHAPTER && x.approved() != null).mapToInt(x -> x.chapterNumber).max().orElse(0);
            require(p.endChapter >= Math.min(written, old.endChapter), "不能从规划中移除已经写出的章节；请保留覆盖范围并修订其内容");
        }
    }
    private boolean unchangedFromBase(Artifact artifact, Version candidate, Fact fact) {
        Version base=baseVersion(artifact,candidate);
        return base != null && base.facts != null && base.facts.contains(fact);
    }
    private Version baseVersion(Artifact artifact, Version candidate) {
        if (candidate.baseVersionId == null) return null;
        return artifact.versions.stream().filter(version -> candidate.baseVersionId.equals(version.id)).findFirst().orElse(null);
    }
    private String actionName(String action) {
        return switch (action) {
            case "OUTLINE" -> "生成全书大纲"; case "CHARACTERS" -> "生成人物设定";
            case "PLAN" -> "生成章节规划"; case "CHAPTER" -> "生成章节正文";
            case "REWRITE" -> "修订内容"; case "REVIEW" -> "一致性检查";
            case "STYLE_REVIEW" -> "文风检查";
            case "COMPLETE" -> "完结检查"; case "CONFIRM" -> "确认当前版本";
            case "REPAIR" -> "处理关联修订"; case "BUDGET" -> "调整字数上限";
            default -> "继续当前步骤";
        };
    }
}
