package com.novelforge.generation;

import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import com.novelforge.novel.Novel.ShadowReview;
import com.novelforge.novel.Novel.ShadowReviewDecision;
import com.novelforge.novel.Novel.ShadowReviewStatus;
import com.novelforge.novel.Novel.Version;
import com.novelforge.shared.Problem;
import com.novelforge.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:shadow-evaluation;DB_CLOSE_DELAY=-1")
class ShadowReviewEvaluationServiceTest {
    @Autowired NovelRepository repository;
    @Autowired WorkflowService workflow;
    @Autowired ShadowReviewEvaluationService evaluations;

    @Test void summarizesFormalOverlapUniqueFindingsTimingAndFeedbackWithoutChangingRevision() {
        ReviewIssue repeated=new ReviewIssue("第一章结尾","证据状态与已确认主线冲突","正文写证据被销毁",
                "保留证据或改写结局依据","必须修正");
        ReviewIssue unique=new ReviewIssue("第一章中段","人物在同一时刻出现在两地","前文仍在码头",
                "补写移动过程","必须修正");
        ReviewIssue advisory=new ReviewIssue("第一章开头","转场略快","两段之间没有过渡",
                "作者可按节奏需要补一句","作者决定");
        Novel created=workflow.create("专业检查统计","验证影子报告对照与评价",1000,"");
        Novel seeded=repository.update(created.id,novel->{
            Artifact chapter=new Artifact(); chapter.kind=Kind.CHAPTER; chapter.chapterNumber=1;
            Version version=new Version(); version.title="第一章"; version.content="正文"; version.summary="摘要";
            version.review=new Review(false,List.of(repeated.text()),false,false,false,List.of(repeated));
            chapter.versions.add(version); novel.artifacts.add(chapter);

            ShadowReview continuity=new ShadowReview(); continuity.artifactId=chapter.id; continuity.versionId=version.id;
            continuity.checker=ContinuityShadowService.CHECKER; continuity.status=ShadowReviewStatus.SUCCEEDED;
            continuity.review=new Review(false,List.of(repeated.text(),unique.text(),advisory.text()),false,false,false,
                    List.of(repeated,unique,advisory));
            continuity.createdAt="2026-01-01T00:00:00Z"; continuity.modelStartedAt="2026-01-01T00:00:00.500Z";
            continuity.finishedAt="2026-01-01T00:00:01Z"; continuity.authorDecision=ShadowReviewDecision.USEFUL;

            ShadowReview plot=new ShadowReview(); plot.artifactId=chapter.id; plot.versionId=version.id;
            plot.checker=PlotForeshadowShadowService.CHECKER; plot.status=ShadowReviewStatus.FAILED;
            plot.error="检查失败"; plot.createdAt="2026-01-01T00:00:00Z";
            plot.modelStartedAt="2026-01-01T00:00:01Z"; plot.finishedAt="2026-01-01T00:00:03Z";
            novel.shadowReviews.add(continuity); novel.shadowReviews.add(plot);
            return novel;
        });

        var summary=evaluations.summary(seeded);
        assertThat(summary.totalReports()).isEqualTo(2);
        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.continuityReports()).isEqualTo(1);
        assertThat(summary.plotForeshadowReports()).isEqualTo(1);
        assertThat(summary.modelCalls()).isEqualTo(2);
        assertThat(summary.formalBlockingFindings()).isEqualTo(1);
        assertThat(summary.shadowBlockingFindings()).isEqualTo(2);
        assertThat(summary.overlappingFindings()).isEqualTo(1);
        assertThat(summary.uniqueShadowFindings()).isEqualTo(1);
        assertThat(summary.useful()).isEqualTo(1);
        assertThat(summary.unreviewed()).isZero();
        assertThat(summary.sampleSize()).isEqualTo(1);
        assertThat(summary.sampleReviewed()).isEqualTo(1);
        assertThat(summary.samplePending()).isZero();
        assertThat(summary.checkerSummaries()).hasSize(2);
        assertThat(summary.averageTurnaroundMillis()).isEqualTo(2000);
        assertThat(summary.averageModelDurationMillis()).isEqualTo(1250);
        assertThat(summary.tokenUsageAvailable()).isFalse();
        assertThat(summary.costAvailable()).isFalse();
        assertThat(summary.reports().getFirst().targetLabel()).isEqualTo("第1章正文");
        assertThat(summary.reports()).filteredOn(report->report.status()==ShadowReviewStatus.SUCCEEDED).singleElement().satisfies(report->{
            assertThat(report.blockingFindings()).isEqualTo(2);
            assertThat(report.advisoryFindings()).isEqualTo(1);
            assertThat(report.overlappingFindings()).isEqualTo(1);
            assertThat(report.uniqueFindings()).isEqualTo(1);
            assertThat(report.evaluationSample()).isTrue();
            assertThat(report.sampleGroup()).contains("前段正文","连续性");
        });

        long revisionBefore=repository.get(created.id).revision;
        String reportId=seeded.shadowReviews.getFirst().id;
        evaluations.feedback(created.id,reportId,"partly_useful","指出了方向，但表述重复");
        Novel reloaded=repository.get(created.id);
        assertThat(reloaded.revision).isEqualTo(revisionBefore);
        assertThat(reloaded.shadowReviews.getFirst().authorDecision).isEqualTo(ShadowReviewDecision.PARTLY_USEFUL);
        assertThat(reloaded.shadowReviews.getFirst().authorNote).isEqualTo("指出了方向，但表述重复");
        assertThat(reloaded.shadowReviews.getFirst().decidedAt).isNotBlank();
    }

    @Test void rejectsUnknownDecisionAndFeedbackForUnfinishedReport() {
        Novel created=workflow.create("评价边界","验证错误评价不会污染数据",1000,"");
        String reportId=repository.update(created.id,novel->{
            ShadowReview report=new ShadowReview(); novel.shadowReviews.add(report); return report.id;
        });
        assertThatThrownBy(()->evaluations.feedback(created.id,reportId,"MAYBE",""))
                .isInstanceOf(Problem.class).hasMessageContaining("评价只能选择");
        assertThatThrownBy(()->evaluations.feedback(created.id,reportId,"USEFUL",""))
                .isInstanceOf(Problem.class).hasMessageContaining("只有已完成");
    }

    @Test void treatsDifferentlyWordedDescriptionsOfTheSameFactConflictAsOverlap() {
        ReviewIssue formal=new ReviewIssue("第十四章头盔交还段落",
                "头盔在拆迁当天已经由陈默保存，但后文又称拆迁后三天才从废墟中清出，时间顺序冲突",
                "正文同时出现拆迁当天保存头盔和拆迁后三天发现头盔",
                "统一头盔发现和交还的先后顺序","必须修正");
        ReviewIssue shadow=new ReviewIssue("第14章旧头盔被发现并交还周海的段落",
                "旧头盔被清出的日期晚于陈默开始保管它的日期，前后时间线无法成立",
                "开头写永宁里拆除当天陈默已经收着头盔，后面写拆迁后的第三天工人才从旧被褥中清出",
                "明确工人清出、陈默认出、保存和交还依次发生","必须修正");
        Novel created=workflow.create("语义重合","验证不同措辞能够归并",1000,"");
        Novel seeded=repository.update(created.id,novel->{
            Artifact chapter=new Artifact(); chapter.kind=Kind.CHAPTER; chapter.chapterNumber=14;
            Version version=new Version(); version.title="第十四章"; version.content="正文"; version.summary="摘要";
            version.review=new Review(false,List.of(formal.text()),false,false,false,List.of(formal));
            chapter.versions.add(version); novel.artifacts.add(chapter);
            ShadowReview report=new ShadowReview(); report.artifactId=chapter.id; report.versionId=version.id;
            report.checker=ContinuityShadowService.CHECKER; report.status=ShadowReviewStatus.SUCCEEDED;
            report.review=new Review(false,List.of(shadow.text()),false,false,false,List.of(shadow));
            novel.shadowReviews.add(report); return novel;
        });

        var summary=evaluations.summary(seeded);
        assertThat(summary.overlappingFindings()).isEqualTo(1);
        assertThat(summary.uniqueShadowFindings()).isZero();
    }
}
