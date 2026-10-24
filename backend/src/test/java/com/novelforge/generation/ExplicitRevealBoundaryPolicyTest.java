package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.ChapterBeat;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.Version;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitRevealBoundaryPolicyTest {
    private final ExplicitRevealBoundaryPolicy policy=new ExplicitRevealBoundaryPolicy();
    private final Review clean=new Review(true,List.of(),false,false,false,List.of());

    @Test void catchesForbiddenTopicInNewChapterEvenBeforeItsArtifactExists() {
        Novel novel=novelWithPlan("火灾仍不可出现。");
        Review result=policy.apply(novel,null,Action.CHAPTER,
                candidate("顾乔翻到一张剪报，上面写着旧楼曾发生火灾。"),clean);
        assertThat(result.passed()).isFalse();
        assertThat(result.issueDetails()).singleElement().satisfies(issue->{
            assertThat(issue.severity()).isEqualTo("必须修正");
            assertThat(issue.evidence()).contains("火灾仍不可出现","旧楼曾发生火灾");
        });
    }

    @Test void catchesDoNotMentionFormButDoesNotBlockUnconfirmedRumorBoundary() {
        Novel novel=novelWithPlan("不得出现火灾、失踪者名单；不得确认邻居隐瞒原因。");
        Artifact target=new Artifact(); target.kind=Kind.CHAPTER; target.chapterNumber=1;
        Review blocked=policy.apply(novel,target,Action.REVIEW,candidate("她发现旧报提到火灾。"),clean);
        assertThat(blocked.passed()).isFalse();
        Review allowed=policy.apply(novel,target,Action.REVIEW,
                candidate("她怀疑邻居回避，也许另有原因。"),clean);
        assertThat(allowed.passed()).isTrue();
    }

    @Test void letsOtherChaptersAndOrdinaryPlanDetailsPass() {
        Novel novel=novelWithPlan("火灾仍不可出现。");
        Artifact target=new Artifact(); target.kind=Kind.CHAPTER; target.chapterNumber=2;
        Review result=policy.apply(novel,target,Action.REVIEW,
                candidate("第二章她终于得知旧楼曾发生火灾。"),clean);
        assertThat(result.passed()).isTrue();
    }

    private Novel novelWithPlan(String revealBoundary) {
        Novel novel=new Novel(); novel.targetWords=5000; novel.approvedMaxWords=5500;
        Novel.Plan plan=new Novel.Plan(); plan.startChapter=1; plan.endChapter=1;
        plan.chapters=List.of(new ChapterBeat(1,"第一章","询问旧邻居",900,
                List.of("询问照片"),revealBoundary,"决定继续寻找"));
        Artifact artifact=new Artifact(); artifact.kind=Kind.PLAN;
        Version version=new Version(); version.title="规划"; version.content="第一章规划";
        version.summary="询问"; version.plan=plan;
        artifact.versions.add(version); artifact.approvedVersionId=version.id;
        novel.artifacts.add(artifact); return novel;
    }

    private ModelGateway.Generated candidate(String content) {
        return new ModelGateway.Generated("第一章",content,"章节摘要",List.of(),null);
    }
}
