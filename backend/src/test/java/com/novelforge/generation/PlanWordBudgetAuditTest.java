package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Plan;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.Version;
import com.novelforge.novel.WordCounter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanWordBudgetAuditTest {
    private final PlanWordBudgetAudit audit=new PlanWordBudgetAudit(new WordCounter());
    private final Review passed=new Review(true,List.of(),false,false,false);

    @Test void blocksNewBatchWhoseSuggestionsCannotFitTheApprovedUpperLimit() {
        Novel novel=novelWithThreeWrittenChapters();
        novel.artifacts.add(approvedPlan(1,4,1000));

        Review result=audit.apply(novel,null,Action.PLAN,candidate(plan(5,9,1000,true)),passed);

        assertThat(result.passed()).isFalse();
        assertThat(result.issueDetails()).singleElement().satisfies(issue->{
            assertThat(issue.problem()).contains("建议字数合计超过");
            assertThat(issue.evidence()).contains("已确认正文 3000 字","未写章节建议约 6000 字", "当前上限 5500 字");
        });
    }

    @Test void excludesAlreadyWrittenBeatsAndAllowsAPlanWithinTheLimit() {
        Novel novel=novelWithThreeWrittenChapters();
        novel.artifacts.add(approvedPlan(1,4,1000));

        Review result=audit.apply(novel,null,Action.PLAN,candidate(plan(5,5,1000,true)),passed);

        assertThat(result.passed()).isTrue();
    }

    @Test void revisionReplacesRatherThanDoubleCountsItsOwnApprovedBatch() {
        Novel novel=novelWithThreeWrittenChapters();
        Artifact prior=approvedPlan(1,4,1000);
        novel.artifacts.add(prior);

        Review result=audit.apply(novel,prior,Action.REWRITE,candidate(plan(1,4,1200,false)),passed);

        assertThat(result.passed()).isTrue();
    }

    private Novel novelWithThreeWrittenChapters() {
        Novel novel=new Novel(); novel.targetWords=5000; novel.approvedMaxWords=5500;
        for(int chapter=1;chapter<=3;chapter++) {
            Artifact artifact=new Artifact(); artifact.kind=Kind.CHAPTER; artifact.chapterNumber=chapter;
            Version version=new Version(); version.content="字".repeat(1000);
            artifact.versions.add(version); artifact.approvedVersionId=version.id;
            novel.artifacts.add(artifact);
        }
        return novel;
    }

    private Artifact approvedPlan(int start,int end,int suggestedWords) {
        Artifact artifact=new Artifact(); artifact.kind=Kind.PLAN;
        Version version=new Version(); version.plan=plan(start,end,suggestedWords,false);
        artifact.versions.add(version); artifact.approvedVersionId=version.id;
        return artifact;
    }

    private Plan plan(int start,int end,int suggestedWords,boolean finalBatch) {
        Plan plan=new Plan(); plan.startChapter=start; plan.endChapter=end; plan.finalBatch=finalBatch;
        plan.chapters=new ArrayList<>();
        for(int chapter=start;chapter<=end;chapter++) plan.chapters.add(new Novel.ChapterBeat(
                chapter,"第"+chapter+"章","剧情推进",suggestedWords,List.of(),"",""));
        return plan;
    }

    private ModelGateway.Generated candidate(Plan plan) {
        return new ModelGateway.Generated("章节规划","正文","摘要",List.of(),plan);
    }
}
