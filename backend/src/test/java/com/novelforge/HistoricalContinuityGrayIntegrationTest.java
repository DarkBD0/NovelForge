package com.novelforge;

import com.novelforge.generation.AgentContextPolicy;
import com.novelforge.generation.HistoricalContinuityGrayService;
import com.novelforge.generation.HistoricalStructuredMemoryShadowService;
import com.novelforge.generation.ModelGateway;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:historical-gray;DB_CLOSE_DELAY=-1",
        "novelforge.experiments.continuity-shadow-enabled=false",
        "novelforge.experiments.historical-continuity-gray-enabled=true",
        "novelforge.experiments.historical-continuity-gray-novel-ids=*",
        "novelforge.experiments.historical-continuity-gray-max-chapters=4"
})
class HistoricalContinuityGrayIntegrationTest {
    @Autowired NovelRepository repository;
    @Autowired WorkflowService workflow;
    @Autowired TaskService tasks;
    @MockitoBean ModelGateway model;
    @MockitoBean HistoricalStructuredMemoryShadowService historical;

    @BeforeEach void baseModel() {
        reset(model,historical);
        when(model.ready()).thenReturn(true); when(model.mode()).thenReturn("http");
        when(historical.enabled()).thenReturn(true);
        when(model.review(any(),any())).thenReturn(new Review(true,List.of(),false,false,false,List.of()));
        when(model.extractState(any(),any())).thenReturn(new ModelGateway.StateExtraction(List.of(
                new ModelGateway.ExtractedState("event_clock","EVENT","人物查看旧钟","ACTIVE",
                        List.of("旧钟楼的机械钟突然恢复了转动。")))));
    }

    @Test void persistsGroundedHistoricalReportWithoutChangingFormalResult() {
        Novel novel=pendingFifthChapter("历史灰度命中");
        var aggregate=aggregate(novel.artifacts.getFirst().approved().id);
        when(historical.evaluate(any(),startsWith("gray-"),eq(List.of(1))))
                .thenReturn(report(novel,aggregate));
        ReviewIssue issue=new ReviewIssue("CONTINUITY:WORLD_RULE:HIGH","第五章候选正文","旧钟状态冲突",
                "冲突对象：旧钟｜冲突属性：世界规则｜原状态时间：第一章｜候选状态时间：第五章｜"
                        +"同一时点：“是”｜推进授权：“无”｜候选原文：“旧钟楼的机械钟突然恢复了转动”｜"
                        +"已确认依据：“旧钟楼的机械钟早已停摆”","说明恢复原因或保持停摆","必须修正");
        when(model.continuityReview(any(),any())).thenReturn(new Review(false,List.of(issue.text()),
                false,false,false,List.of(issue)));

        Artifact chapter=novel.artifacts.getLast();
        Task task=tasks.submit(novel.id,Action.REVIEW,chapter.id,"",Novel.uid(),novel.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->completedWithShadow(novel.id,task.id));

        Novel saved=repository.get(novel.id); Version version=saved.artifacts.getLast().latest();
        ShadowReview shadow=saved.shadowReviews.stream().filter(item->HistoricalContinuityGrayService.CHECKER.equals(item.checker))
                .findFirst().orElseThrow();
        assertThat(version.review.passed()).isTrue();
        assertThat(shadow.status).isEqualTo(ShadowReviewStatus.SUCCEEDED);
        assertThat(shadow.review.issueDetails()).hasSize(1);
        assertThat(saved.agentRuns).anySatisfy(run->{
            if("shadow-historical-continuity-review".equals(run.operation)) {
                assertThat(run.contextPolicyVersion).isEqualTo(AgentContextPolicy.STRUCTURED_MEMORY_SHADOW_VERSION);
                assertThat(run.contextJson).contains("formalStructuredMemory");
            }
        });
    }

    @Test void reconstructionFailureFallsBackAndCandidateCanStillBeConfirmed() {
        Novel novel=pendingFifthChapter("历史灰度回退");
        when(historical.evaluate(any(),startsWith("gray-"),eq(List.of(1))))
                .thenThrow(new IllegalStateException("reconstruction unavailable"));
        Artifact chapter=novel.artifacts.getLast();
        Task task=tasks.submit(novel.id,Action.REVIEW,chapter.id,"",Novel.uid(),novel.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->completedWithShadow(novel.id,task.id));

        Novel saved=repository.get(novel.id); Version version=saved.artifacts.getLast().latest();
        ShadowReview shadow=saved.shadowReviews.stream().filter(item->HistoricalContinuityGrayService.CHECKER.equals(item.checker))
                .findFirst().orElseThrow();
        assertThat(version.review.passed()).isTrue();
        assertThat(shadow.status).isEqualTo(ShadowReviewStatus.FAILED);
        assertThat(shadow.error).contains("自动回退");
        Novel confirmed=workflow.confirm(saved.id,saved.artifacts.getLast().id,version.id,saved.revision,null);
        assertThat(confirmed.artifacts.getLast().clean()).isTrue();
    }

    private boolean completedWithShadow(String novelId,String taskId) {
        Novel saved=repository.get(novelId);
        return tasks.find(saved,taskId).status==TaskStatus.SUCCEEDED && saved.shadowReviews.stream()
                .anyMatch(item->HistoricalContinuityGrayService.CHECKER.equals(item.checker)
                        && item.status!=ShadowReviewStatus.RUNNING);
    }

    private HistoricalStructuredMemoryShadowService.Aggregate aggregate(String versionId) {
        var evidence=new HistoricalStructuredMemoryShadowService.ShadowEvidence(
                "旧钟楼的机械钟早已停摆",versionId,1);
        return new HistoricalStructuredMemoryShadowService.Aggregate("MODEL_DERIVED_HISTORICAL_SHADOW",
                List.of(new HistoricalStructuredMemoryShadowService.ShadowFact("old_clock","WORLD",
                        "旧钟停摆","CURRENT",1,1,List.of(versionId),List.of(evidence))),List.of(),List.of(),"只读影子");
    }

    private HistoricalStructuredMemoryShadowService.Report report(Novel novel,
            HistoricalStructuredMemoryShadowService.Aggregate aggregate) {
        return new HistoricalStructuredMemoryShadowService.Report("SUCCEEDED",true,
                "SELECTED_CONFIRMED_CHAPTERS_READ_ONLY",HistoricalStructuredMemoryShadowService.POLICY_VERSION,
                "gray-memory",novel.revision,List.of(1),1,List.of(),aggregate,"不写入");
    }

    private Novel pendingFifthChapter(String title) {
        Novel novel=workflow.create(title,"验证历史连续性灰度",5000,"");
        return repository.update(novel.id,n->{
            n.autoStyleEnabled=false;
            for(int number=1;number<=5;number++) {
                Artifact artifact=new Artifact(); artifact.kind=Kind.CHAPTER; artifact.chapterNumber=number;
                Version version=new Version(); version.title="第"+number+"章";
                version.content=number==1?"旧钟楼的机械钟早已停摆。":number==5
                        ?"旧钟楼的机械钟突然恢复了转动。":"第"+number+"章已经发生的事情。";
                version.summary="第"+number+"章摘要"; version.facts=List.of(); version.basedOnRevision=n.revision;
                artifact.versions.add(version);
                if(number<5) artifact.approvedVersionId=version.id;
                n.artifacts.add(artifact);
            }
            n.revision++; return n;
        });
    }
}
