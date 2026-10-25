package com.novelforge.generation;

import com.novelforge.novel.Novel.ChapterBeat;
import com.novelforge.novel.Novel.Plan;
import com.novelforge.novel.Novel.ResolutionStep;

/** Keeps the reader-facing plan and its summary derived from the same chapter beats. */
public final class PlanNarrativeRenderer {
    private PlanNarrativeRenderer() {}

    public static ModelGateway.Generated normalize(ModelGateway.Generated generated) {
        if(generated==null || generated.plan()==null) return generated;
        Plan plan=generated.plan();
        if(plan.finalBatch) plan.triggerReason="本批完成主线与明确结局";
        else plan.chapters.stream().filter(beat->beat.number()==plan.prepareNextAfterChapter)
                .findFirst().ifPresent(beat->plan.triggerReason="第"+beat.number()+"章后："
                        +shorten(value(beat.endingHook()).isBlank()?beat.purpose():beat.endingHook(),200));
        return new ModelGateway.Generated(generated.title(),render(plan),summary(plan),
                generated.facts(),plan,generated.outlineSpec(),
                generated.draftIssues().stream().filter(issue->!issue.contains("内容摘要缺失")).toList());
    }

    public static String render(Plan plan) {
        StringBuilder text=new StringBuilder("第").append(plan.startChapter).append("章至第")
                .append(plan.endChapter).append("章章节规划");
        for(ChapterBeat chapter:plan.chapters) {
            text.append("\n\n第").append(chapter.number()).append("章 ").append(chapter.title())
                    .append("\n核心变化：").append(chapter.purpose());
            if(chapter.targetWords()!=null) text.append("\n建议字数：").append(chapter.targetWords()).append("字");
            if(!chapter.sceneBeats().isEmpty()) {
                text.append("\n场景节点：");
                for(int i=0;i<chapter.sceneBeats().size();i++)
                    text.append("\n").append(i+1).append(". ").append(chapter.sceneBeats().get(i));
            }
            if(!chapter.revealBoundary().isBlank()) text.append("\n揭示边界：").append(chapter.revealBoundary());
            if(!chapter.endingHook().isBlank()) text.append("\n章末局面：").append(chapter.endingHook());
        }
        if(plan.finalBatch && plan.resolutionSteps!=null && !plan.resolutionSteps.isEmpty()) {
            text.append("\n\n结局如何落实：");
            for(ResolutionStep step:plan.resolutionSteps)
                text.append("\n- 第").append(step.chapterNumber()).append("章：").append(value(step.action()))
                        .append("；依据：").append(value(step.evidenceSource()))
                        .append("；结果：").append(value(step.expectedResult()))
                        .append("；判断边界：").append(value(step.certaintyBoundary()));
        }
        text.append("\n\n批次衔接：").append(value(plan.handoff));
        if(!plan.finalBatch && !value(plan.triggerReason).isBlank())
            text.append("\n提前规划下一批的时机：").append(plan.triggerReason)
                    .append("（下一批正文仍从第").append(plan.endChapter+1).append("章开始）");
        if(!value(plan.assumptions).isBlank()) text.append("\n待复核假设：").append(plan.assumptions);
        return text.toString();
    }

    public static String summary(Plan plan) {
        StringBuilder text=new StringBuilder("第").append(plan.startChapter).append("章至第")
                .append(plan.endChapter).append("章：");
        int size=plan.chapters.size();
        for(int i=0;i<size;i++) {
            if(i>=6 && i<size-2) {
                if(i==6) text.append("中间章节按逐章规划推进；");
                continue;
            }
            ChapterBeat beat=plan.chapters.get(i);
            text.append("第").append(beat.number()).append("章").append(beat.title()).append("，")
                    .append(shorten(beat.purpose(),160)).append("；");
        }
        text.append("批次衔接：").append(shorten(plan.handoff,300));
        return shorten(text.toString(),4900);
    }

    private static String shorten(String value,int limit) {
        String text=value(value).strip();
        return text.length()<=limit?text:text.substring(0,limit-1)+"…";
    }

    private static String value(String value) { return value==null?"":value; }
}
