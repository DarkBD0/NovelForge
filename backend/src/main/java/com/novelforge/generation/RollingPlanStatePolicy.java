package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.ChapterBeat;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Plan;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Keeps automatic upstream repair from silently rewriting an already approved future mainline. */
@Component
public class RollingPlanStatePolicy {
    private static final Pattern EXPLICIT_REPLAN=Pattern.compile(
            "重排|重构|改写.{0,8}(?:未来)?主线|调整.{0,8}(?:未来)?主线|修改.{0,8}章节目的|改变.{0,8}章节安排|允许调整未来主线");

    public Review apply(Novel novel, Artifact target, Action action, String instructions,
                        ModelGateway.Generated candidate, Review review) {
        if (target==null || target.kind!=Kind.PLAN || action!=Action.REWRITE || !target.needsRevision
                || target.approved()==null || candidate==null || candidate.plan()==null
                || EXPLICIT_REPLAN.matcher(instructions==null?"":instructions).find()) return review;
        Plan approved=target.approved().plan, proposed=candidate.plan();
        List<String> changes=protectedChanges(approved,proposed);
        if (changes.isEmpty()) return review;
        int confirmedThrough=novel.artifacts.stream().filter(a->a.kind==Kind.CHAPTER&&a.clean())
                .mapToInt(a->a.chapterNumber).max().orElse(0);
        return review.withIssue(new ReviewIssue("当前章节规划 > 已确认未来主线",
                "本次应当校准实际正文与未来规划的承接，但候选同时改动了已确认的未来主线："+String.join("、",changes),
                "当前已确认正文截至第 "+confirmedThrough+" 章；原规划的章节目的和揭示边界仍是已确认设计，不是已经发生的事实，也不能在承接校准中静默重写",
                "恢复原规划的章节范围、章节目的和揭示边界，只更新承接说明、条件假设，以及紧邻下一章为适配已发生正文所需的场景入口；如确需改动未来主线，请由作者明确提出“允许调整未来主线”",
                "必须修正"));
    }

    private List<String> protectedChanges(Plan old,Plan next) {
        List<String> result=new ArrayList<>();
        if (old==null || next==null) return List.of("规划主体");
        if (old.startChapter!=next.startChapter || old.endChapter!=next.endChapter || old.finalBatch!=next.finalBatch)
            result.add("批次范围或是否最终批");
        if (old.chapters==null || next.chapters==null || old.chapters.size()!=next.chapters.size()) {
            result.add("章节数量"); return result;
        }
        for (int i=0;i<old.chapters.size();i++) {
            ChapterBeat before=old.chapters.get(i),after=next.chapters.get(i);
            if (before==null || after==null || before.number()!=after.number()) {
                result.add("章节编号"); continue;
            }
            if (!Objects.equals(before.title(),after.title()) || !Objects.equals(before.purpose(),after.purpose()))
                result.add("第"+before.number()+"章标题或核心目的");
            if (!Objects.equals(before.revealBoundary(),after.revealBoundary()))
                result.add("第"+before.number()+"章揭示边界");
            if (!Objects.equals(before.endingHook(),after.endingHook()))
                result.add("第"+before.number()+"章章末局面");
        }
        return result.stream().distinct().limit(8).toList();
    }
}
