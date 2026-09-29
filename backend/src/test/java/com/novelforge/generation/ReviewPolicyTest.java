package com.novelforge.generation;

import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import com.novelforge.novel.Novel.Version;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewPolicyTest {
    private final ReviewPolicy policy=new ReviewPolicy();
    private ReviewIssue issue(String severity) {
        return new ReviewIssue("当前内容","问题","依据","建议",severity);
    }

    @Test void authorDecisionAndSuggestionCannotBlockConfirmation() {
        Review model=new Review(false,List.of("取舍","润色"),false,false,false,
                List.of(issue("作者决定"),issue("建议优化")));
        assertThat(policy.normalize(model).passed()).isTrue();
    }

    @Test void preservesPassedLegacyReportWhoseTextHasNoReliableSeverity() {
        Review legacy=new Review(true,List.of("离线演示检查仅验证流程"),false,false,false);
        assertThat(policy.normalize(legacy).passed()).isTrue();
    }

    @Test void removesBlockingFindingThatQuotesTextOnlyPresentInOldDraft() {
        Version old=new Version();old.content="那根插销从傍晚起就没有再被拉开过。";
        var candidate=new ModelGateway.Generated("第四章","四点二十三分，那根插销从午后起就没有再被拉开过。","摘要",List.of(),null);
        var stale=new ReviewIssue("第四章正文","同一段内时间表述自相矛盾：一句说插销‘从傍晚起’没有再被拉开",
                "当前段落发生在四点二十三分","删除错误时间表述","必须修正");
        Review normalized=policy.normalize(new Review(false,List.of(stale.text()),false,false,false,List.of(stale)),candidate,old);
        assertThat(normalized.passed()).isTrue();
        assertThat(normalized.issueDetails()).isEmpty();
    }

    @Test void keepsBlockingFindingWhoseQuotedTextStillExistsInCurrentDraft() {
        Version old=new Version();old.content="插销‘从傍晚起’没有再被拉开。";
        var candidate=new ModelGateway.Generated("第四章","四点二十三分，插销‘从傍晚起’没有再被拉开。","摘要",List.of(),null);
        var current=new ReviewIssue("第四章正文","正文写有‘从傍晚起’，与四点二十三分矛盾",
                "两处时间互斥","改成午后","必须修正");
        Review normalized=policy.normalize(new Review(false,List.of(current.text()),false,false,false,List.of(current)),candidate,old);
        assertThat(normalized.passed()).isFalse();
        assertThat(normalized.issueDetails()).containsExactly(current);
    }

    @Test void doesNotTreatAReportedOmissionAsStaleOldText() {
        Version old=new Version();old.content="必须出现‘警报响起’。";
        var candidate=new ModelGateway.Generated("第四章","房间里一片安静。","摘要",List.of(),null);
        var missing=new ReviewIssue("第四章正文","正文没有落实规划要求‘警报响起’",
                "章节规划明确要求该事件","补写事件","必须修正");
        Review normalized=policy.normalize(new Review(false,List.of(missing.text()),false,false,false,List.of(missing)),candidate,old);
        assertThat(normalized.passed()).isFalse();
    }

    @Test void removesBlockingCurrentTextClaimWhenItsQuoteIsNotInTheCandidate() {
        Version old=new Version(); old.content="旧稿写着‘门已经反锁’，走廊里没有人。";
        var candidate=new ModelGateway.Generated("第四章","新稿写着门虚掩着，走廊尽头传来脚步声。","摘要",List.of(),null);
        var stale=new ReviewIssue("第四章正文","当前正文写着‘门已经反锁’，与人物进入房间冲突",
                "候选原文：“门已经反锁”｜已确认依据：“人物进入房间”","删除反锁描述","必须修正");

        Review normalized=policy.normalize(new Review(false,List.of(stale.text()),false,false,false,List.of(stale)),candidate,old);

        assertThat(normalized.passed()).isTrue();
        assertThat(normalized.issueDetails()).isEmpty();
    }

    @Test void removesUngroundedPositiveClaimEvenWhenThereIsNoPreviousVersion() {
        var candidate=new ModelGateway.Generated("第四章","门虚掩着，走廊尽头传来脚步声。","摘要",List.of(),null);
        var invented=new ReviewIssue("第四章正文","候选正文出现互相冲突的门锁状态",
                "候选原文：“门已经反锁”｜已确认依据：“门应当打开”","统一门锁状态","必须修正");

        Review normalized=policy.normalize(new Review(false,List.of(invented.text()),false,false,false,List.of(invented)),candidate,null);

        assertThat(normalized.passed()).isTrue();
        assertThat(normalized.issueDetails()).isEmpty();
    }

    @Test void keepsGroundedConflictWhenCandidateQuoteExists() {
        var candidate=new ModelGateway.Generated("第四章","门已经反锁，任何人都无法进入。","摘要",List.of(),null);
        var conflict=new ReviewIssue("第四章正文","当前正文写着门已反锁，与已确认行动冲突",
                "候选原文：“门已经反锁”｜已确认依据：“林秋随后推门进入”","改成门虚掩着","必须修正");

        Review normalized=policy.normalize(new Review(false,List.of(conflict.text()),false,false,false,List.of(conflict)),candidate,null);

        assertThat(normalized.passed()).isFalse();
        assertThat(normalized.issueDetails()).containsExactly(conflict);
    }

    @Test void removesArchiveClaimThatDoesNotQuoteTheCurrentCandidate() {
        var candidate=new ModelGateway.Generated("第四章","门虚掩着。","摘要",
                List.of(new com.novelforge.novel.Novel.Fact("door","EVENT","房门处于虚掩状态","ACTIVE")),null);
        var invented=new ReviewIssue("当前章节 > 档案增量","档案声明房门已经反锁",
                "当前档案原文：“房门已经反锁”｜已确认依据：“人物随后进入”","修改档案","必须修正");

        Review normalized=policy.normalize(new Review(false,List.of(invented.text()),false,false,false,List.of(invented)),candidate,null);

        assertThat(normalized.passed()).isTrue();
        assertThat(normalized.issueDetails()).isEmpty();
    }

    @Test void redirectsMetadataEvidenceFixAwayFromNovelProse() {
        var fact=new com.novelforge.novel.Novel.Fact("card","EVENT","陈默把储存卡交给林秋","ACTIVE");
        var candidate=new ModelGateway.Generated("第四章","陈默收起储存卡。","摘要",List.of(fact),null);
        var issue=new ReviewIssue("当前章节 > 档案增量","档案声明超出正文依据",
                "当前档案原文：“陈默把储存卡交给林秋”","在正文补写一句说明储存卡已经移交","必须修正");

        Review normalized=policy.normalize(new Review(false,List.of(issue.text()),false,false,false,List.of(issue)),candidate,null);

        assertThat(normalized.issueDetails()).singleElement().satisfies(result->{
            assertThat(result.suggestion()).contains("只修改摘要或档案","不要向正文补写检查说明");
            assertThat(result.suggestion()).doesNotContain("在正文补写");
        });
    }
}
