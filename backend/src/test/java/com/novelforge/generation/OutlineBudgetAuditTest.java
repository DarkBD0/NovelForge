package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class OutlineBudgetAuditTest {
    private final OutlineBudgetAudit audit=new OutlineBudgetAudit();
    private Novel novel() { Novel n=new Novel();n.targetWords=30_000;n.approvedMaxWords=33_000;return n; }
    private ModelGateway.Generated outline(String content) {
        return new ModelGateway.Generated("大纲",content,"摘要",List.of(),null);
    }
    private Review passed() { return new Review(true,List.of(),false,false,false); }

    @Test void acceptsActTotalBelowTargetBecauseTargetIsGuidance() {
        Review result=audit.apply(novel(),null,Action.OUTLINE,outline("""
                第一幕：第1—8章，约7000字。
                第二幕：第9—19章，约10000字。
                第三幕：第20—27章，约7000字。
                第四幕：第28—30章，约3000字。
                """),passed());
        assertThat(result.passed()).isTrue();
        assertThat(result.issueDetails()).isEmpty();
    }

    @Test void acceptsBudgetInsideApprovedRangeAndSupportsFullWidthDigits() {
        Review result=audit.apply(novel(),null,Action.OUTLINE,outline("""
                第一幕：约８，５００字。
                第二幕：约１１５００字。
                第三幕：约8500字。
                第四幕：约3500字。
                """),passed());
        assertThat(result.passed()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test void localArithmeticOverridesAnUnsupportedModelBudgetComplaint() {
        Review model=new Review(false,List.of("模型误判"),false,false,false,List.of(new ReviewIssue(
                "阶段目标与字数分配","四阶段合计为33000字，但分项相加为32000字",
                "分项为8500、11500、8500、3500","统一总数","必须修正")));
        ModelGateway.Generated candidate=new ModelGateway.Generated("大纲","""
                第一幕：约8500字。
                第二幕：约11500字。
                第三幕：约8500字。
                第四幕：约3500字。
                ""","全书约三点二万字",List.of(),null);
        Review result=audit.apply(novel(),null,Action.OUTLINE,candidate,model);
        assertThat(result.passed()).isTrue();
        assertThat(result.issueDetails()).isEmpty();
    }

    @Test void rejectsARealMismatchBetweenDeclaredTotalAndActSum() {
        Review result=audit.apply(novel(),null,Action.OUTLINE,new ModelGateway.Generated("大纲","""
                第一幕：约8500字。
                第二幕：约11500字。
                第三幕：约8500字。
                第四幕：约3500字。
                ""","本大纲规划三十章、约三点三万字",List.of(),null),passed());
        assertThat(result.passed()).isFalse();
        assertThat(result.issueDetails()).singleElement().extracting(ReviewIssue::problem)
                .asString().contains("33000字","32000字");
    }

    @Test void approvedMaximumIsNotMistakenForADeclaredTotal() {
        assertThat(audit.declaredTotal("全书不超过三点三万字，实际规划约三点二万字")).isEqualTo(32_000);
    }

    @Test void rejectsActTotalAboveApprovedMaximum() {
        Review result=audit.apply(novel(),null,Action.OUTLINE,
                outline("第一幕：约20000字。\n第二幕：约14000字。"),passed());
        assertThat(result.passed()).isFalse();
        assertThat(result.issueDetails().getFirst().problem()).contains("34000字","超过当前批准上限33000字");
    }
}
