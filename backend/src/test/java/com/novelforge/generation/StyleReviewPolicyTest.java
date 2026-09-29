package com.novelforge.generation;

import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StyleReviewPolicyTest {
    private final StyleReviewPolicy policy=new StyleReviewPolicy();

    @Test void fullTimestampAndDecorativePanoramaBecomeNonBlockingAdvice() {
        String content="消息发送于二零二一年七月十一日二十三点五十九分。\n\n"+
                "他看着早餐铺开门，看着老人拉卷帘门，看着老板搬货。";
        Review result=policy.normalize(new Review(true,List.of(),false,false,false),content);

        assertThat(result.passed()).isTrue();
        assertThat(result.issueDetails()).extracting(ReviewIssue::problem)
                .anyMatch(text->text.contains("完整年月日和分钟"))
                .anyMatch(text->text.contains("连续罗列多个"));
        assertThat(result.issueDetails()).extracting(ReviewIssue::severity)
                .allMatch(level->level.equals("建议优化")||level.equals("作者决定"));
    }

    @Test void modelCannotTurnAStyleOpinionIntoAConfirmationBlocker() {
        ReviewIssue blocking=new ReviewIssue("正文","句子可以更短","原文：“正文”","删除后半句","必须修正");
        Review result=policy.normalize(new Review(false,List.of(blocking.text()),false,false,false,List.of(blocking)),"正文");

        assertThat(result.passed()).isTrue();
        assertThat(result.issueDetails()).singleElement().extracting(ReviewIssue::severity).isEqualTo("建议优化");
    }

    @Test void discardsStyleIssueWhoseEvidenceComesFromAnotherVersion() {
        ReviewIssue current=new ReviewIssue("当前章","存在重复","原文：“他把底单放回文件袋。”","删除重复句","建议优化");
        ReviewIssue stale=new ReviewIssue("上一章","存在重复","原文：“她给出的每句话都像真话。”","删除重复句","作者决定");

        Review result=policy.normalize(new Review(true,List.of(),false,false,false,List.of(current,stale)),
                "他把底单放回文件袋。窗外还在下雨。");

        assertThat(result.issueDetails()).extracting(ReviewIssue::location)
                .containsExactly("当前章");
    }

    @Test void discardsModelIssueWithoutExactQuotedEvidence() {
        ReviewIssue vague=new ReviewIssue("正文","存在重复","这段有点啰嗦","建议删减","建议优化");

        Review result=policy.normalize(new Review(true,List.of(),false,false,false,List.of(vague)),"正文内容");

        assertThat(result.issueDetails()).isEmpty();
    }

    @Test void detectsMachineExplanationAndKeepsAmbiguousNegativeProofForTheAuthor() {
        String content="这段记录仅用于核对档案。储存卡没有再次移交。";

        Review result=policy.normalize(new Review(true,List.of(),false,false,false),content);

        assertThat(result.issueDetails()).anySatisfy(issue->{
            assertThat(issue.location()).isEqualTo("正文中的核对式说明");
            assertThat(issue.severity()).isEqualTo("建议优化");
        }).anySatisfy(issue->{
            assertThat(issue.location()).isEqualTo("正文中的否定式证明");
            assertThat(issue.severity()).isEqualTo("作者决定");
        });
    }
}
