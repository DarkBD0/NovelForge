package com.novelforge;

import com.novelforge.generation.AgentRole;
import com.novelforge.generation.ModelGateway;
import com.novelforge.generation.PlotForeshadowShadowService;
import com.novelforge.generation.ProfessionalReviewPolicy;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.task.TaskService;
import com.novelforge.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:plot-foreshadow-shadow;DB_CLOSE_DELAY=-1",
        "novelforge.experiments.continuity-shadow-enabled=true",
        "novelforge.experiments.plot-foreshadow-shadow-enabled=true"
})
class PlotForeshadowShadowIntegrationTest {
    @Autowired NovelRepository repository;
    @Autowired WorkflowService workflow;
    @Autowired TaskService tasks;
    @MockitoBean ModelGateway model;

    @BeforeEach void mockModel() {
        when(model.ready()).thenReturn(true);
        when(model.mode()).thenReturn("http");
        when(model.review(any(),any())).thenReturn(new Review(true,List.of(),false,false,false,List.of()));
        when(model.extractState(any(),any())).thenReturn(new ModelGateway.StateExtraction(List.of(
                new ModelGateway.ExtractedState("event_take_evidence","EVENT","人物取得证据后离开仓库","ACTIVE",
                        List.of("他取得证据后离开仓库。")))));
        when(model.continuityReview(any(),any())).thenReturn(new Review(true,List.of(),false,false,false,List.of()));
        when(model.outlineFoundation(any())).thenReturn(new ModelGateway.Generated("人物与世界参谋材料",
                "世界规则和人物骨架","参谋摘要",List.of(),null));
        when(model.outlineContinuityReview(any(),any())).thenReturn(new Review(true,List.of(),false,false,false,List.of()));
        when(model.outlinePlotReview(any(),any())).thenReturn(new Review(true,List.of(),true,true,true,List.of()));
    }

    @Test void persistsPlotReportWithoutReplacingFormalReviewOrBlockingConfirmation() {
        ReviewIssue issue=new ReviewIssue("第一章结尾","关键结果与已确认主线因果互相排斥",
                "候选原文：“他取得证据后离开仓库”｜已确认依据：“结局依靠同一证据公开真相”",
                "保留证据或改写证明方式","必须修正");
        when(model.plotForeshadowReview(any(),any()))
                .thenReturn(new Review(false,List.of(issue.text()),false,false,false,List.of(issue)));
        Novel novel=pendingChapter("情节伏笔影子报告"); Artifact chapter=novel.artifacts.getFirst();

        Task submitted=tasks.submit(novel.id,Action.REVIEW,chapter.id,"",Novel.uid(),novel.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->{
            Novel saved=repository.get(novel.id);
            return tasks.find(saved,submitted.id).status==TaskStatus.SUCCEEDED
                    && saved.shadowReviews.size()==2
                    && saved.shadowReviews.stream().allMatch(item->item.status==ShadowReviewStatus.SUCCEEDED)
                    && saved.tasks.stream().noneMatch(item->item.status==TaskStatus.QUEUED
                    || item.status==TaskStatus.RUNNING)
                    && saved.shadowReviews.stream().anyMatch(item->PlotForeshadowShadowService.CHECKER.equals(item.checker)
                    && item.status==ShadowReviewStatus.SUCCEEDED);
        });

        Novel saved=repository.get(novel.id); Version version=saved.artifacts.getFirst().latest();
        ShadowReview shadow=saved.shadowReviews.stream()
                .filter(item->PlotForeshadowShadowService.CHECKER.equals(item.checker)).findFirst().orElseThrow();
        assertThat(version.review.passed()).isTrue();
        assertThat(shadow.review.passed()).isTrue();
        assertThat(shadow.review.issueDetails()).singleElement().satisfies(savedIssue->{
            assertThat(savedIssue.severity()).isEqualTo("作者决定");
            assertThat(savedIssue.suggestion()).contains("专业检查已将其降为作者决定");
        });
        assertThat(shadow.policyVersion).isEqualTo(ProfessionalReviewPolicy.VERSION);
        assertThat(saved.agentRuns).anySatisfy(run->{
            assertThat(run.role).isEqualTo(AgentRole.PLOT_FORESHADOW_AUDITOR.name());
            assertThat(run.operation).isEqualTo("shadow-plot-foreshadow-review");
            assertThat(run.status).isEqualTo(AgentRunStatus.SUCCEEDED);
        });
        Novel confirmed=workflow.confirm(saved.id,saved.artifacts.getFirst().id,version.id,saved.revision,null);
        assertThat(confirmed.artifacts.getFirst().clean()).isTrue();
    }

    @Test void outlineUsesFormalDedicatedAuditsWithoutDuplicateShadowReports() {
        ModelGateway.Generated outline=OutlineTestFixtures.generated(1000,"主人公因线索开始调查，最终公开证据并结束主线",
                "主人公因线索开始调查，最终公开证据并结束主线。");
        when(model.generate(any())).thenReturn(outline);
        Novel novel=workflow.create("大纲影子失败隔离","验证大纲正式链路不受影子故障影响",1000,"");

        Task submitted=tasks.submit(novel.id,Action.OUTLINE,null,"",Novel.uid(),novel.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->{
            Novel saved=repository.get(novel.id);
            return tasks.find(saved,submitted.id).status==TaskStatus.SUCCEEDED
                    && saved.outlinePipelines.size()==1
                    && saved.outlinePipelines.getFirst().status==OutlinePipelineStatus.SUCCEEDED;
        });

        Novel saved=repository.get(novel.id); Version version=saved.artifacts.getFirst().latest();
        assertThat(version.review.passed()).isTrue();
        assertThat(saved.shadowReviews).isEmpty();
        Novel confirmed=workflow.confirm(saved.id,saved.artifacts.getFirst().id,version.id,saved.revision,null);
        assertThat(confirmed.artifacts.getFirst().clean()).isTrue();
        verify(model,times(1)).outlineFoundation(any());
        verify(model,times(1)).outlineContinuityReview(any(),any());
        verify(model,times(1)).outlinePlotReview(any(),any());
        verify(model,never()).plotForeshadowReview(any(),any());
        verify(model,never()).continuityReview(any(),any());
    }

    private Novel pendingChapter(String title) {
        Novel novel=workflow.create(title,"验证情节与伏笔影子检查",1000,"");
        return repository.update(novel.id,n->{
            Artifact chapter=new Artifact(); chapter.kind=Kind.CHAPTER; chapter.chapterNumber=1;
            Version version=new Version(); version.title="第一章"; version.content="他取得证据后离开仓库。";
            version.summary="人物取得证据并离开仓库"; version.facts=List.of(); version.basedOnRevision=n.revision;
            chapter.versions.add(version); n.artifacts.add(chapter); n.revision++;
            return n;
        });
    }
}
