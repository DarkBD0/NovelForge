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
