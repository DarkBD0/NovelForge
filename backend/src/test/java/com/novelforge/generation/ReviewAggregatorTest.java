package com.novelforge.generation;

import com.novelforge.novel.ExplicitWordLimit;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import com.novelforge.novel.WordCounter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewAggregatorTest {
    private final WordCounter words=new WordCounter();
    private final ReviewAggregator aggregator=new ReviewAggregator(words,new ExplicitWordLimit(),
            new OutlineBudgetAudit(),new FactAtomicityPolicy(),new ProseBoundaryPolicy(),new RollingPlanStatePolicy(),
            new ReviewConvergence(),new ReviewPolicy());

    @Test
    void deduplicatesEquivalentFindingsAndKeepsTheStrongerSeverity() {
        ReviewIssue advice=new ReviewIssue("第一章，正文","人物选择缺少依据。","原文没有交代。","补充依据","建议优化");
        ReviewIssue blocking=new ReviewIssue("第一章正文","人物选择缺少依据","原文没有交代","补充依据","必须修正");
        Review raw=new Review(false,List.of(advice.text(),blocking.text()),false,false,false,List.of(advice,blocking));

        Review result=aggregator.aggregate(novel(),chapter(),Action.REVIEW,"",candidate("正文"),null,raw);

        assertThat(result.issueDetails()).singleElement().satisfies(issue->
                assertThat(issue.severity()).isEqualTo("必须修正"));
        assertThat(result.passed()).isFalse();
    }

    @Test
    void classifiesAuthorDecisionsAndSuggestionsAsNonBlocking() {
        ReviewIssue author=new ReviewIssue("正文","动机是否需要加强","属于文学取舍","由作者判断","作者决定");
        ReviewIssue suggestion=new ReviewIssue("正文","句子略长","不影响事实","可适当压缩","建议优化");
        Review raw=new Review(false,List.of(author.text(),suggestion.text()),false,false,false,List.of(author,suggestion));

        Review result=aggregator.aggregate(novel(),chapter(),Action.REVIEW,"",candidate("正文"),null,raw);

        assertThat(result.passed()).isTrue();
        assertThat(result.issueDetails()).hasSize(2);
    }

    @Test
    void addsTheExistingDeterministicChapterLimitRuleExactlyOnce() {
        Review raw=new Review(true,List.of(),false,false,false,List.of());
        String content="一二三四五六七八九十一";

        Review result=aggregator.aggregate(novel(),chapter(),Action.REVIEW,"最多10字",candidate(content),null,raw);

        assertThat(words.count(content)).isEqualTo(11);
        assertThat(result.passed()).isFalse();
        assertThat(result.issueDetails()).singleElement().satisfies(issue->{
            assertThat(issue.problem()).contains("单章上限");
            assertThat(issue.severity()).isEqualTo("必须修正");
        });
    }

    @Test
    void validatesAdvisoryReportsAndRemovesDuplicatesWithoutMakingThemBlocking() {
        ReviewIssue issue=new ReviewIssue("正文","解释重复","两句含义相同","删除一句","建议优化");
        Review result=aggregator.aggregateAdvisory(new Review(true,List.of(issue.text(),issue.text()),false,false,false,
                List.of(issue,issue)));

        assertThat(result.passed()).isTrue();
        assertThat(result.issueDetails()).containsExactly(issue);
        assertThatThrownBy(()->aggregator.aggregateAdvisory(null)).hasMessageContaining("检查报告缺少必要字段");
    }

    @Test
    void planWithContractuallyEmptyFactsIsNotBlockedByMissingFactFinding() {
        Artifact planArtifact=new Artifact(); planArtifact.kind=Kind.PLAN; planArtifact.batchNumber=2;
        Novel.Plan plan=new Novel.Plan(); plan.startChapter=6; plan.endChapter=7;
        plan.prepareNextAfterChapter=6; plan.triggerReason="进入下一阶段"; plan.handoff="承接前文";
        plan.assumptions="依据实际正文复核";
        plan.chapters=List.of(new Novel.ChapterBeat(6,"追查","取得线索"),
                new Novel.ChapterBeat(7,"转折","发现阻碍"));
        ReviewIssue falseFinding=new ReviewIssue("当前内容 > 档案增量","档案增量缺失",
                "facts 为空","补充本次内容已经发生的事实变化","必须修正");
        Review raw=new Review(false,List.of(falseFinding.text()),false,false,false,List.of(falseFinding));
        var generated=new ModelGateway.Generated("第二批规划","未来章节安排","规划摘要",List.of(),plan);

        Review result=aggregator.aggregate(novel(),planArtifact,Action.PLAN,"",generated,null,raw);

        assertThat(result.passed()).isTrue();
        assertThat(result.issueDetails()).isEmpty();
    }

    @Test
    void shadowReviewDropsBlockingClaimWhoseCandidateQuoteDoesNotExist() {
        ReviewIssue invented=new ReviewIssue("当前章节","候选正文出现相反描述",
                "候选原文：“根本不存在的旧句”｜已确认依据：“既有事实”","修改当前章节","必须修正");
        Review raw=new Review(false,List.of(invented.text()),false,false,false,List.of(invented));

        Review result=aggregator.aggregateShadow(raw,candidate("当前正文没有那句话。"));

        assertThat(result.passed()).isTrue();
        assertThat(result.issueDetails()).isEmpty();
    }

    @Test
    void doesNotTreatInternalArchiveFormattingAsAProseFailure() {
        Review raw=new Review(true,List.of(),false,false,false,List.of());
        var generated=new ModelGateway.Generated("第一章","王阿姨藏起现金后离开。","摘要",
                List.of(new Novel.Fact("cash","EVENT","王阿姨藏起现金，并把钥匙交给张洋","ACTIVE")),null);

        Review result=aggregator.aggregate(novel(),chapter(),Action.CHAPTER,"",generated,null,raw);

        assertThat(result.passed()).isTrue();
        assertThat(result.issueDetails()).isEmpty();
    }

    private Novel novel() {
        Novel novel=new Novel();novel.targetWords=1000;novel.approvedMaxWords=1100;return novel;
    }

    private Artifact chapter() {
        Artifact artifact=new Artifact();artifact.kind=Kind.CHAPTER;artifact.chapterNumber=1;return artifact;
    }

    private ModelGateway.Generated candidate(String content) {
        return new ModelGateway.Generated("第一章",content,"摘要",List.of(),null);
    }
}
