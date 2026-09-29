package com.novelforge.generation;

import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Review;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProseBoundaryPolicyTest {
    private final ProseBoundaryPolicy policy=new ProseBoundaryPolicy();

    @Test void blocksCheckerFacingExplanationInsideChapterProse() {
        Artifact chapter=new Artifact(); chapter.kind=Kind.CHAPTER;
        var candidate=new ModelGateway.Generated("第一章",
                "陈默收起储存卡。这段说明仅用于核对档案。随后他离开房间。","摘要",List.of(),null);

        Review result=policy.apply(chapter,Action.REVIEW,candidate,
                new Review(true,List.of(),false,false,false,List.of()));

        assertThat(result.passed()).isFalse();
        assertThat(result.issueDetails()).singleElement().satisfies(issue->{
            assertThat(issue.evidence()).contains("这段说明仅用于核对档案");
            assertThat(issue.suggestion()).contains("删除这句机器说明","保存到摘要或档案");
        });
    }

    @Test void doesNotApplyTheChapterBoundaryToPlans() {
        Artifact plan=new Artifact(); plan.kind=Kind.PLAN;
        var candidate=new ModelGateway.Generated("规划","该字段仅用于核对规划。","摘要",List.of(),null);
        Review original=new Review(true,List.of(),false,false,false,List.of());

        assertThat(policy.apply(plan,Action.PLAN,candidate,original)).isSameAs(original);
    }
}
