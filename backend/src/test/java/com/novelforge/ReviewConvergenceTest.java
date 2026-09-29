package com.novelforge;

import com.novelforge.generation.ReviewConvergence;
import com.novelforge.novel.Novel.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewConvergenceTest {
    private final ReviewConvergence convergence=new ReviewConvergence();

    @Test void sharedMatcherKeepsUnrelatedProblemsAtTheSameLocationSeparate() {
        ReviewIssue timeline=new ReviewIssue("第一章结尾","人物尚未到达码头却已经在码头打电话",
                "上一段人物仍在旧城区","补充移动过程","必须修正");
        ReviewIssue identity=new ReviewIssue("第一章结尾","来电者姓名与人物设定不一致",
                "人物设定中来电者姓林","统一人物姓名","必须修正");
        assertThat(convergence.sameIssue(timeline,identity)).isFalse();
    }

    @Test void assignsStableIdentityAndReusesItForAParaphrasedFinding() {
        Artifact artifact=new Artifact(); artifact.kind=Kind.CHAPTER;
        ReviewIssue oldIssue=new ReviewIssue("第十三章储存卡交接段落","储存卡在本章再次移交，与上一章已经交给警方冲突",
                "上一章写明储存卡原件已经交给警方","删除重复移交，只保留核对回执","必须修正");
        Version old=new Version(); old.review=convergence.apply(null,review(oldIssue),0);
        artifact.versions.add(old);
        ReviewIssue paraphrased=new ReviewIssue("第十三章储存卡交接部分","候选又写把储存卡交给警方，重复了上一章的移交",
                "已确认正文说明储存卡原件由警方保管","改为核对材料，不要再次交卡","必须修正");

        Review current=convergence.apply(artifact,review(paraphrased),1);

        assertThat(current.issueDetails().getFirst().issueId()).isEqualTo(old.review.issueDetails().getFirst().issueId());
        assertThat(current.issueDetails().getFirst().severity()).isEqualTo("必须修正");
        assertThat(current.passed()).isFalse();
    }

    @Test void secondGuidedRoundTurnsOnlyTheRepeatedFindingIntoAuthorDecision() {
        Artifact artifact=new Artifact(); artifact.kind=Kind.CHAPTER;
        ReviewIssue repeated=new ReviewIssue("第十三章储存卡交接段落","储存卡再次移交，与上一章冲突",
                "上一章已经交给警方","删除重复移交","必须修正");
        Version old=new Version(); old.review=convergence.apply(null,review(repeated),0); artifact.versions.add(old);

        Review result=convergence.apply(artifact,review(new ReviewIssue("第十三章储存卡交接段落",
                "储存卡再次移交，与上一章冲突","上一章已经交给警方","删除重复移交","必须修正")),2);

        assertThat(result.passed()).isTrue();
        assertThat(result.issueDetails()).singleElement().satisfies(issue->{
            assertThat(issue.severity()).isEqualTo("作者决定");
            assertThat(issue.suggestion()).contains("两轮自动修订","停止自动循环");
            assertThat(issue.issueId()).isEqualTo(old.review.issueDetails().getFirst().issueId());
        });
    }

    @Test void secondRoundDoesNotDowngradeANewUnrelatedBlockingFinding() {
        Artifact artifact=new Artifact(); artifact.kind=Kind.CHAPTER;
        Version old=new Version(); old.review=convergence.apply(null,review(new ReviewIssue("储存卡交接段落",
                "储存卡重复移交","上一章已移交","删除重复内容","必须修正")),0); artifact.versions.add(old);

        Review result=convergence.apply(artifact,review(new ReviewIssue("结尾人物行动",
                "人物在受伤后突然跑步，行动能力前后冲突","上一段写明腿部骨折","改为由他人搀扶","必须修正")),2);

        assertThat(result.passed()).isFalse();
        assertThat(result.issueDetails().getFirst().severity()).isEqualTo("必须修正");
        assertThat(result.issueDetails().getFirst().issueId()).isNotEqualTo(old.review.issueDetails().getFirst().issueId());
    }

    @Test void supportsAReviewWithoutPreviousVersion() {
        Review current=convergence.apply(null,new Review(true,List.of(),false,false,false),0);
        assertThat(current.passed()).isTrue();
        assertThat(current.issueDetails()).isEmpty();
    }

    private Review review(ReviewIssue issue) {
        return new Review(false,List.of(issue.text()),false,false,false,List.of(issue));
    }
}
