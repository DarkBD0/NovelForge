package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProfessionalReviewPolicyTest {
    private final ProfessionalReviewPolicy policy=new ProfessionalReviewPolicy(new ObjectMapper(),new ReviewConvergence());

    @Test void removesFindingWhenClaimedCandidateQuoteDoesNotExist() {
        ReviewIssue issue=issue("候选原文：“不存在的句子”｜已确认依据：“周海把卡交给警方”","必须修正");
        Review result=policy.normalize(review(issue),candidate("陈默保存储存卡。"),request("周海把卡交给警方"),review(issue));
        assertThat(result.issueDetails()).isEmpty();
        assertThat(result.passed()).isTrue();
    }

    @Test void downgradesUnverifiedAuthorityOrUnconfirmedUniqueFinding() {
        ReviewIssue missingAuthority=issue("候选原文：“陈默保存储存卡”｜已确认依据：“不存在的依据”","必须修正");
        Review first=policy.normalize(review(missingAuthority),candidate("陈默保存储存卡。"),request("周海把卡交给警方"),review(missingAuthority));
        assertThat(first.issueDetails()).singleElement().extracting(ReviewIssue::severity).isEqualTo("作者决定");

        ReviewIssue unique=issue("候选原文：“陈默保存储存卡”｜已确认依据：“周海把卡交给警方”","必须修正");
        Review second=policy.normalize(review(unique),candidate("陈默保存储存卡。"),request("周海把卡交给警方"),
                new Review(true,List.of(),false,false,false,List.of()));
        assertThat(second.issueDetails()).singleElement().extracting(ReviewIssue::severity).isEqualTo("作者决定");
        assertThat(second.passed()).isTrue();
    }

    @Test void downgradesNarrativeOmissionEvenWhenFormalReviewMentionsTheSameIssue() {
        ReviewIssue issue=new ReviewIssue("结尾","正文没有写出完整司法过程，处理结果只是由人物口述带过",
                "候选原文：“陈默保存储存卡”｜已确认依据：“周海把卡交给警方”","补写司法过程","必须修正");
        Review result=policy.normalize(review(issue),candidate("陈默保存储存卡。"),request("周海把卡交给警方"),review(issue));
        assertThat(result.issueDetails()).singleElement().satisfies(item->{
            assertThat(item.severity()).isEqualTo("作者决定");
            assertThat(item.suggestion()).contains("叙事取舍");
        });
    }

    @Test void keepsIndependentlyConfirmedGroundedHardConflict() {
        ReviewIssue issue=issue("候选原文：“陈默保存储存卡”｜已确认依据：“周海把卡交给警方”","必须修正");
        Review result=policy.normalize(review(issue),candidate("陈默保存储存卡。"),request("周海把卡交给警方"),review(issue));
        assertThat(result.issueDetails()).singleElement().extracting(ReviewIssue::severity).isEqualTo("必须修正");
        assertThat(result.passed()).isFalse();
    }

    @Test void treatsAnOpeningSceneFollowedByALaterSceneAsAuthorJudgmentEvenWhenFormalReviewAgrees() {
        ReviewIssue issue=new ReviewIssue("开头与后文会面","开头写拆除当天，随后会面写拆迁第三天取得头盔，时间先后混乱",
                "候选原文：“拆迁第三天取得头盔”｜已确认依据：“旧楼按期拆除”","统一两个场景时间","必须修正");
        Review result=policy.normalize(review(issue),candidate("开头是拆除当天。拆迁第三天取得头盔。"),
                request("旧楼按期拆除"),review(issue));
        assertThat(result.issueDetails()).singleElement().extracting(ReviewIssue::severity).isEqualTo("作者决定");
    }

    @Test void dropsAdvisoryFindingWhoseAuthorityQuoteCannotBeVerified() {
        ReviewIssue issue=issue("候选原文：“陈默保存储存卡”｜已确认依据：“不存在的依据”","作者决定");
        Review result=policy.normalize(review(issue),candidate("陈默保存储存卡。"),request("周海把卡交给警方"),null);
        assertThat(result.issueDetails()).isEmpty();
    }

    private ReviewIssue issue(String evidence,String severity) {
        return new ReviewIssue("储存卡段落","储存卡状态与已确认事实冲突",evidence,"统一储存卡状态",severity);
    }

    private Review review(ReviewIssue issue) {
        return new Review(false,List.of(issue.text()),false,false,false,List.of(issue));
    }

    private ModelGateway.Generated candidate(String content) {
        return new ModelGateway.Generated("第十章",content,"摘要",List.of(),null);
    }

    private ModelGateway.Request request(String authority) {
        String json="{\"acceptedReferences\":[{\"content\":\""+authority+"\"}]}";
        return new ModelGateway.Request(Action.REVIEW,new Novel(),null,
                new ContextAssembler.Context(json,List.of(),1,1),"");
    }
}
