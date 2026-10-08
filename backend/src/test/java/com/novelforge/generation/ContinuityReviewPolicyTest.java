package com.novelforge.generation;

import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContinuityReviewPolicyTest {
    private final ContinuityReviewPolicy policy=new ContinuityReviewPolicy();

    @Test void keepsAHighConfidenceSameTimeObjectLocationConflict() {
        ReviewIssue issue=issue("CONTINUITY:OBJECT_LOCATION:HIGH","钥匙在同一发现事件中出现于两个地点",
                "把候选中的发现位置改回外卖箱夹层");
        Review result=policy.normalize(review(issue));
        assertThat(result.issueDetails()).containsExactly(issue);
    }

    @Test void dropsUnresolvedToResolvedProgressionAndWeatherChange() {
        ReviewIssue judicial=issue("CONTINUITY:ABSOLUTE_TIME:HIGH",
                "上一章仍在接受核查，本章已经判刑，属于状态跳变","补写中间程序");
        ReviewIssue weather=issue("CONTINUITY:ABSOLUTE_TIME:HIGH",
                "上一章下雨，本章雨已经停了","交代天气变化");
        assertThat(policy.normalize(review(judicial,weather)).issueDetails()).isEmpty();
    }

    @Test void dropsNarrativeAdviceAndFindingsWithoutRecognizableConflictType() {
        ReviewIssue narrative=issue("CONTINUITY:ABILITY_BOUNDARY:HIGH",
                "人物从派出所出来但前文缺少过渡","补一句说明为什么去派出所");
        ReviewIssue unknown=new ReviewIssue("当前段落","描述不够自然",
                proof().replace("冲突属性：位置","冲突属性：叙述节奏"),"调整表达","作者决定");
        assertThat(policy.normalize(review(narrative,unknown)).issueDetails()).isEmpty();
    }

    @Test void infersWhitelistedIdWhenModelOmitsItButStructuredAttributeIsUnambiguous() {
        ReviewIssue missingId=new ReviewIssue("当前段落","钥匙位置冲突",proof(),"统一位置","必须修正");
        Review result=policy.normalize(review(missingId));
        assertThat(result.issueDetails()).singleElement().satisfies(issue->{
            assertThat(issue.issueId()).isEqualTo("CONTINUITY:OBJECT_LOCATION:HIGH");
            assertThat(issue.problem()).isEqualTo("钥匙位置冲突");
        });
    }

    @Test void infersRelationForExplicitColleagueAndDelegationAttributes() {
        String evidence="冲突对象：林澈与周岚｜冲突属性：是否共事、是否受托｜原状态时间：第一章｜候选状态时间：第二章｜"
                +"同一时点：“是”｜推进授权：“无”｜候选原文：“以前从没共事过”｜已确认依据：“二人共事四年”";
        ReviewIssue missingId=new ReviewIssue("当前段落","共事和委托状态互斥",evidence,"统一关系状态","必须修正");
        Review result=policy.normalize(review(missingId));
        assertThat(result.issueDetails()).singleElement()
                .extracting(ReviewIssue::issueId).isEqualTo("CONTINUITY:RELATION:HIGH");
    }

    @Test void infersAbsoluteTimeForExplicitShiftRange() {
        String evidence="冲突对象：周海值班时段｜冲突属性：具体起止时间｜原状态时间：第十章｜候选状态时间：当前章节｜"
                +"同一时点：“是”｜推进授权：“无”｜候选原文：“上午十点到下午六点”｜"
                +"已确认依据：“夜间十点到次日清晨六点”";
        ReviewIssue missingId=new ReviewIssue("当前段落","同一次值班的起止时间互斥",evidence,
                "统一值班时段","必须修正");
        assertThat(policy.normalize(review(missingId)).issueDetails()).singleElement()
                .extracting(ReviewIssue::issueId).isEqualTo("CONTINUITY:ABSOLUTE_TIME:HIGH");
    }

    @Test void infersObjectLocationForFirstAcquisitionSource() {
        String evidence="冲突对象：铜钥匙｜冲突属性：首次获得来源｜原状态时间：第一章｜候选状态时间：当前章节｜"
                +"同一时点：“是”｜推进授权：“无”｜候选原文：“在旧船舱首次发现”｜"
                +"已确认依据：“信里只有一枚铜钥匙”";
        ReviewIssue missingId=new ReviewIssue("当前段落","铜钥匙的首次获得来源互斥",evidence,
                "统一首次获得来源","必须修正");
        assertThat(policy.normalize(review(missingId)).issueDetails()).singleElement()
                .extracting(ReviewIssue::issueId).isEqualTo("CONTINUITY:OBJECT_LOCATION:HIGH");
    }

    @Test void keepsAtMostTwoHighConfidenceFindings() {
        ReviewIssue first=issue("CONTINUITY:IDENTITY:HIGH","人物身份互斥","统一身份");
        ReviewIssue second=issue("CONTINUITY:RELATION:HIGH","人物关系互斥","统一关系");
        ReviewIssue third=issue("CONTINUITY:OBJECT_OWNERSHIP:HIGH","物品归属互斥","统一归属");
        assertThat(policy.normalize(review(first,second,third)).issueDetails()).containsExactly(first,second);
    }

    @Test void acceptsEquivalentAsciiPunctuationInStructuredProof() {
        String evidence="冲突对象:钥匙|冲突属性:位置|原状态时间:第二章|候选状态时间:第二章|"
                +"同一时点:\"是\"|推进授权:\"不存在\"|候选原文：“钥匙在车座下”｜已确认依据：“钥匙在外卖箱”";
        ReviewIssue issue=new ReviewIssue("CONTINUITY:OBJECT_LOCATION:HIGH","当前内容","钥匙位置互斥",
                evidence,"统一钥匙发现位置","作者决定");
        assertThat(policy.normalize(review(issue)).issueDetails()).containsExactly(issue);
    }

    @Test void dropsObjectExclusivityInventedByTheReviewer() {
        String evidence="冲突对象：铜钥匙｜冲突属性：开启地点｜原状态时间：第三章｜候选状态时间：当前章节｜"
                +"同一时点：“是”｜推进授权：“无”｜候选原文：“曾用它核对塔底暗门的锁孔”｜"
                +"已确认依据：“后来用铜钥匙打开维护间”";
        ReviewIssue issue=new ReviewIssue("CONTINUITY:OBJECT_LOCATION:HIGH","当前内容",
                "候选把铜钥匙的唯一开启对象写成塔底暗门",evidence,"改成维护间","必须修正");
        assertThat(policy.normalize(review(issue)).issueDetails()).isEmpty();
    }

    private ReviewIssue issue(String id,String problem,String suggestion) {
        return new ReviewIssue(id,"当前内容",problem,proof(),suggestion,"作者决定");
    }

    private String proof() {
        return "冲突对象：钥匙｜冲突属性：位置｜原状态时间：第二章｜候选状态时间：第二章｜"
                +"同一时点：“是”｜推进授权：“无”｜候选原文：“钥匙在车座下”｜已确认依据：“钥匙在外卖箱”";
    }

    private Review review(ReviewIssue...issues) {
        return new Review(true,List.of(issues).stream().map(ReviewIssue::text).toList(),false,false,false,List.of(issues));
    }
}
