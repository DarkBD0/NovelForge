package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Plan;
import com.novelforge.novel.Novel.ResolutionStep;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;

import java.util.List;

/** Requires a final plan to say how each approved ending goal can actually be reached. */
final class EndingGoalPlanAudit {
    private EndingGoalPlanAudit() {}

    static Review apply(Novel novel,Artifact target,Action action,ModelGateway.Generated candidate,Review review) {
        if((action!=Action.PLAN && (target==null || target.kind!=Kind.PLAN))
                || candidate==null || candidate.plan()==null || !candidate.plan().finalBatch) return review;
        Plan plan=candidate.plan();
        List<ResolutionStep> steps=plan.resolutionSteps==null?List.of():plan.resolutionSteps;
        for(EndingGoalContract.Goal goal:EndingGoalContract.from(novel)) {
            List<ResolutionStep> matches=steps.stream().filter(step->step!=null && goal.id().equals(step.goalId())).toList();
            if(matches.isEmpty()) {
                review=review.withIssue(new ReviewIssue("最终批次规划 > 结局落实",
                        "尚未安排核心结局目标：“"+goal.goal()+"”",
                        "已确认大纲要求：“"+goal.goal()+"”；成立条件：“"+goal.proofStandard()+"”",
                        "在本批结局落实安排中写明章节、人物行动、可信信息来源、预期结果和仍不能推断的边界；并让对应章节规划承接。若篇幅或证据不足，不要宣布全书完结，请修订规划或由作者调整预算。",
                        "必须修正"));
                continue;
            }
            for(ResolutionStep step:matches) {
                boolean inRange=step.chapterNumber()>=plan.startChapter && step.chapterNumber()<=plan.endChapter;
                boolean chapterExists=plan.chapters!=null && plan.chapters.stream()
                        .anyMatch(beat->beat!=null && beat.number()==step.chapterNumber());
                if(!inRange || !chapterExists) review=review.withIssue(new ReviewIssue("最终批次规划 > 结局落实",
                        "结局落实章节没有对应的章节规划：“"+goal.goal()+"”",
                        "规划章节为第 "+step.chapterNumber()+" 章，本批范围为第 "+plan.startChapter+" 至 "+plan.endChapter+" 章",
                        "将落实步骤安排到本批实际存在的章节，并同步该章核心变化","必须修正"));
            }
        }
        return review;
    }
}
