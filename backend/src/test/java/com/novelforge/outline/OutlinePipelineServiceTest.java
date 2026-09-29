package com.novelforge.outline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutlinePipelineServiceTest {
    private final OutlinePipelineService service=new OutlinePipelineService(null,new ObjectMapper());

    @Test
    void mergeDeduplicatesReportsAndOnlyMandatoryIssuesBlockConfirmation() {
        ReviewIssue conflict=new ReviewIssue("世界规则 > 能力代价","高潮绕过了既定代价",
                "候选原文：“每次使用都会失忆”｜冲突原文：“连续使用且毫无代价”",
                "让高潮承担既定代价","必须修正");
        ReviewIssue choice=new ReviewIssue("第二阶段","支线篇幅是否保留由作者决定",
                "候选原文：“调查支线持续三阶段”","作者决定保留或压缩","作者决定");
        Review continuity=new Review(false,List.of(conflict.text()),false,false,false,List.of(conflict));
        Review plot=new Review(false,List.of(conflict.text(),choice.text()),true,true,true,List.of(conflict,choice));

        Review merged=service.merge(continuity,plot);

        assertThat(merged.passed()).isFalse();
        assertThat(merged.issueDetails()).containsExactly(conflict,choice);
        assertThat(merged.mainlineResolved()).isTrue();
        assertThat(merged.endingClear()).isTrue();
        assertThat(merged.foreshadowingResolved()).isTrue();
    }

    @Test
    void advisoryIssuesDoNotBlockOutlineCandidate() {
        ReviewIssue choice=new ReviewIssue("人物弧线","可以考虑增加一次选择压力",
                "候选原文：“主角最终承认错误”","由作者判断是否需要强化","作者决定");

        Review merged=service.merge(new Review(true,List.of(),false,false,false,List.of()),
                new Review(true,List.of(choice.text()),true,true,true,List.of(choice)));

        assertThat(merged.passed()).isTrue();
        assertThat(merged.issueDetails()).containsExactly(choice);
    }
}
