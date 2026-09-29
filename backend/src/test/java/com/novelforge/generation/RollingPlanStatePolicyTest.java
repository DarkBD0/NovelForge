package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RollingPlanStatePolicyTest {
    private final RollingPlanStatePolicy policy=new RollingPlanStatePolicy();

    @Test void allowsHandoffAndNextSceneCalibrationWithoutRewritingFutureMainline() {
        Novel novel=novelWithConfirmedChapter();
        Artifact target=affectedPlan(plan("原承接","原假设","取得钥匙","打开暗门"));
        Plan calibrated=plan("根据第一章实际结果调整承接","正文确认后复核", "取得钥匙","打开暗门");
        calibrated.chapters=setFirstScenes(calibrated.chapters,List.of("从已确认地点继续追查"));

        Review result=policy.apply(novel,target,Action.REWRITE,"根据已确认正文校准承接",
                generated(calibrated),cleanReview());

        assertThat(result.passed()).isTrue();
        assertThat(result.issueDetails()).isEmpty();
    }

    @Test void blocksSilentFutureMainlineRewriteDuringCalibration() {
        Novel novel=novelWithConfirmedChapter();
        Artifact target=affectedPlan(plan("原承接","原假设","取得钥匙","打开暗门"));
        Plan rewritten=plan("新承接","新假设","放弃钥匙","提前公开真相");

        Review result=policy.apply(novel,target,Action.REWRITE,"根据已确认正文校准承接",
                generated(rewritten),cleanReview());

        assertThat(result.passed()).isFalse();
        assertThat(result.issueDetails()).singleElement().satisfies(issue->{
            assertThat(issue.problem()).contains("未来主线","第2章标题或核心目的");
            assertThat(issue.evidence()).contains("已确认正文截至第 1 章","不是已经发生的事实");
            assertThat(issue.suggestion()).contains("允许调整未来主线");
        });
    }

    @Test void permitsBroadReplanOnlyWhenAuthorExplicitlyRequestsIt() {
        Novel novel=novelWithConfirmedChapter();
        Artifact target=affectedPlan(plan("原承接","原假设","取得钥匙","打开暗门"));
        Plan rewritten=plan("新承接","新假设","放弃钥匙","提前公开真相");

        Review result=policy.apply(novel,target,Action.REWRITE,"允许调整未来主线，并重排后续章节",
                generated(rewritten),cleanReview());

        assertThat(result.passed()).isTrue();
    }

    private Novel novelWithConfirmedChapter() {
        Novel novel=new Novel(); Artifact chapter=new Artifact(); chapter.kind=Kind.CHAPTER; chapter.chapterNumber=1;
        Version version=new Version(); version.title="第一章"; version.content="正文"; version.summary="已发生事实";
        chapter.versions.add(version); chapter.approvedVersionId=version.id; novel.artifacts.add(chapter); return novel;
    }

    private Artifact affectedPlan(Plan plan) {
        Artifact artifact=new Artifact(); artifact.kind=Kind.PLAN; artifact.batchNumber=1; artifact.needsRevision=true;
        Version approved=new Version(); approved.title="规划"; approved.content="规划内容"; approved.summary="摘要"; approved.plan=plan;
        artifact.versions.add(approved); artifact.approvedVersionId=approved.id; return artifact;
    }

    private Plan plan(String handoff,String assumptions,String chapter2Purpose,String chapter3Purpose) {
        Plan plan=new Plan(); plan.startChapter=2; plan.endChapter=3; plan.prepareNextAfterChapter=2;
        plan.finalBatch=false; plan.triggerReason="转折点"; plan.handoff=handoff; plan.assumptions=assumptions;
        plan.chapters=List.of(beat(2,"追查",chapter2Purpose),beat(3,"深入",chapter3Purpose)); return plan;
    }

    private ChapterBeat beat(int number,String title,String purpose) {
        return new ChapterBeat(number,title,purpose,1800,List.of("原场景"),"不提前揭示幕后人物","取得新线索");
    }

    private List<ChapterBeat> setFirstScenes(List<ChapterBeat> beats,List<String> scenes) {
        ChapterBeat first=beats.getFirst();
        return List.of(new ChapterBeat(first.number(),first.title(),first.purpose(),first.targetWords(),scenes,
                first.revealBoundary(),first.endingHook()),beats.get(1));
    }

    private ModelGateway.Generated generated(Plan plan) {
        return new ModelGateway.Generated("规划","规划内容","摘要",List.of(),plan);
    }

    private Review cleanReview() { return new Review(true,List.of(),false,false,false,List.of()); }
}
