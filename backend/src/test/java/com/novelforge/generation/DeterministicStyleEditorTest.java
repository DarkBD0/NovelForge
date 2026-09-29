package com.novelforge.generation;

import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import com.novelforge.novel.Novel.Version;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicStyleEditorTest {
    private final DeterministicStyleEditor editor=new DeterministicStyleEditor();

    @Test void deletesOnlyUniqueExactRedundantExplanation() {
        Version version=version("她摇了摇头。她真的想不起来了。门外又响起脚步声。");
        version.styleReview=review(new ReviewIssue("摇头后的解释句","动作之后又重复解释同一结论",
                "原文：“她真的想不起来了。”","删除这句重复解释","建议优化"));

        var result=editor.apply(version);

        assertThat(result.content()).isEqualTo("她摇了摇头。门外又响起脚步声。");
        assertThat(result.summary()).isEqualTo(version.summary);
    }

    @Test void leavesAmbiguousReplacementAndAuthorDecisionToTheAuthor() {
        Version version=version("消息发送于二零二一年七月十一日二十三点五十九分。");
        version.styleReview=review(
                new ReviewIssue("时间句","时间写得过细","原文：“消息发送于二零二一年七月十一日二十三点五十九分。”",
                        "改成当晚，或由作者保留","作者决定"),
                new ReviewIssue("环境段","环境描写略显重复","原文：“消息发送于二零二一年七月十一日二十三点五十九分。”",
                        "精简或替换这一句","建议优化"));

        assertThat(editor.canApply(version)).isFalse();
    }

    @Test void refusesTruncatedOrNonexistentEvidence() {
        Version version=version("他看见街口亮着一盏灯，然后转身离开。");
        version.styleReview=review(new ReviewIssue("街口","旁白重复解释",
                "原文：“他看见街口亮着一盏灯……”","删除重复解释","建议优化"));

        assertThat(editor.canApply(version)).isFalse();
    }

    @Test void refusesToDeleteShortJudgmentOrSentenceThatCarriesRecordedFact() {
        Version shortJudgment=version("这不是还钱。陈默拨通电话。");
        shortJudgment.summary="陈默判断这不是单纯还钱，随后拨通电话。";
        shortJudgment.styleReview=review(new ReviewIssue("判断句","重复解释同一判断",
                "原文：“这不是还钱。”","删除这句重复解释","建议优化"));
        Version recordedFact=version("照片里能看见打开的信封和一叠现金。陈默收起相机。");
        recordedFact.summary="陈默拍到打开的信封和一叠现金。";
        recordedFact.styleReview=review(new ReviewIssue("照片说明","重复解释照片内容",
                "原文：“照片里能看见打开的信封和一叠现金。”","删除这句重复解释","建议优化"));

        assertThat(editor.canApply(shortJudgment)).isFalse();
        assertThat(editor.canApply(recordedFact)).isFalse();
    }

    private Version version(String content) {
        Version version=new Version(); version.title="第一章"; version.content=content;
        version.summary="人物离开现场。"; version.facts=List.of(); return version;
    }

    private Review review(ReviewIssue... issues) {
        List<ReviewIssue> details=List.of(issues);
        return new Review(true,details.stream().map(ReviewIssue::text).toList(),false,false,false,details);
    }
}
