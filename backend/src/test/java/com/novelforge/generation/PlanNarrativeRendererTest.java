package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.ChapterBeat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanNarrativeRendererTest {
    @Test void replacesStaleFreeTextWithTheCurrentChapterBeats() {
        Novel.Plan plan=new Novel.Plan(); plan.startChapter=1; plan.endChapter=2;
        plan.handoff="第一份录用到手，下一批开始竞争"; plan.triggerReason="按作者要求删除旧版就业中心内容";
        plan.prepareNextAfterChapter=1;
        plan.chapters=List.of(new ChapterBeat(1,"走进报告厅","主角受挫后争取临时面试"),
                new ChapterBeat(2,"第一份录用","面试后拿到第一份录用通知"));
        ModelGateway.Generated stale=new ModelGateway.Generated("开篇规划","第一章在宿舍查手机，第二章去就业中心",
                "第二章仍只确认无人报名",List.of(),plan,null,List.of("内容摘要缺失或为空，请补全摘要后重新检查"));

        ModelGateway.Generated normalized=PlanNarrativeRenderer.normalize(stale);

        assertThat(normalized.content()).contains("第1章 走进报告厅","第2章 第一份录用")
                .doesNotContain("就业中心","查手机");
        assertThat(normalized.summary()).contains("第1章走进报告厅","第2章第一份录用")
                .doesNotContain("仍只确认无人报名");
        assertThat(normalized.draftIssues()).isEmpty();
        assertThat(normalized.plan().triggerReason).doesNotContain("按作者要求","就业中心");
        assertThat(normalized.content()).doesNotContain("按作者要求")
                .contains("提前规划下一批的时机：第1章后：")
                .contains("下一批正文仍从第3章开始");
    }
}
