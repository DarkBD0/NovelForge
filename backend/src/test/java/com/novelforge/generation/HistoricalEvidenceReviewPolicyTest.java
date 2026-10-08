package com.novelforge.generation;

import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalEvidenceReviewPolicyTest {
    private final HistoricalEvidenceReviewPolicy policy=new HistoricalEvidenceReviewPolicy();

    @Test void keepsOnlyFindingsWithVerifiedHistoricalQuote() {
        ReviewIssue grounded=issue("已确认依据：“旧钟楼机械钟早已停摆”");
        ReviewIssue invented=issue("已确认依据：“旧钟始终正常运行”");
        Review input=new Review(false,List.of(grounded.text(),invented.text()),false,false,false,
                List.of(grounded,invented));
        var evidence=new HistoricalStructuredMemoryShadowService.ShadowEvidence(
                "旧钟楼机械钟早已停摆","version-1",1);
        var aggregate=new HistoricalStructuredMemoryShadowService.Aggregate("MODEL_DERIVED_HISTORICAL_SHADOW",
                List.of(new HistoricalStructuredMemoryShadowService.ShadowFact("clock","WORLD","停摆","CURRENT",
                        1,1,List.of("version-1"),List.of(evidence))),List.of(),List.of(),"只读");

        Review result=policy.retainGrounded(input,aggregate);

        assertThat(result.issueDetails()).containsExactly(grounded);
        assertThat(result.passed()).isFalse();
    }

    private ReviewIssue issue(String evidence) {
        return new ReviewIssue("CONTINUITY:WORLD_RULE:HIGH","候选正文","状态冲突",evidence,"修正","必须修正");
    }
}
