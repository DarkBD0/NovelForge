package com.novelforge.generation;

import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
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

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:professional-replay;DB_CLOSE_DELAY=-1")
class ProfessionalReviewReplayServiceTest {
    @Autowired NovelRepository repository;
    @Autowired WorkflowService workflow;
    @Autowired SourceSnapshotFactory snapshots;
    @Autowired ProfessionalReviewReplayService replays;
    @MockitoBean ModelGateway model;

    @BeforeEach void modelReady() {
        when(model.ready()).thenReturn(true);
        when(model.mode()).thenReturn("http");
        when(model.continuityReview(any(),any())).thenReturn(clean());
        when(model.plotForeshadowReview(any(),any())).thenReturn(clean());
    }

    @Test void replaysFixedSamplesSeparatelyAndIsIdempotent() {
        Novel novel=seed();
        long revision=novel.revision;
        int oldReports=novel.shadowReviews.size();

        ProfessionalReviewReplayBatch started=replays.start(novel.id,"v2-fixed-sample");
        assertThat(started.items).hasSize(2);
        await().atMost(Duration.ofSeconds(10)).until(()->
                replays.find(novel.id,started.id).status==ShadowReviewStatus.SUCCEEDED);

        Novel saved=repository.get(novel.id);
        ProfessionalReviewReplayBatch completed=replays.find(novel.id,started.id);
        assertThat(completed.policyVersion).contains(ContinuityReviewPolicy.VERSION,ProfessionalReviewPolicy.VERSION);
        assertThat(completed.items).allSatisfy(item->{
            assertThat(item.status).isEqualTo(ReplayItemStatus.SUCCEEDED);
            assertThat(item.review).isNotNull();
        });
        assertThat(saved.revision).isEqualTo(revision);
        assertThat(saved.shadowReviews).hasSize(oldReports);
        assertThat(saved.professionalReviewReplays).hasSize(1);

        ProfessionalReviewReplayBatch duplicate=replays.start(novel.id,"v2-fixed-sample");
        assertThat(duplicate.id).isEqualTo(started.id);
        verify(model,times(1)).continuityReview(any(),any());
        verify(model,times(1)).plotForeshadowReview(any(),any());
    }

    private Novel seed() {
        Novel created=workflow.create("对照重放","验证历史候选不会被修改",1000,"");
        return repository.update(created.id,novel->{
            Artifact chapter=new Artifact(); chapter.kind=Kind.CHAPTER; chapter.chapterNumber=1;
            Version version=new Version(); version.title="第一章"; version.content="陈默仍站在码头。";
            version.summary="陈默留在码头"; version.facts=List.of(); version.review=clean();
            chapter.versions.add(version); novel.artifacts.add(chapter);

            Task task=new Task(); task.action=Action.CHAPTER; task.status=TaskStatus.SUCCEEDED;
            var context=new ContextAssembler.Context("{\"title\":\"对照重放\",\"acceptedReferences\":[\"陈默仍站在码头。\"]}",
                    List.of(),1,0);
            SourceSnapshot source=snapshots.capture(task.id,novel.revision,Action.CHAPTER,chapter.id,context);
            task.sourceSnapshotId=source.id; novel.tasks.add(task); novel.sourceSnapshots.add(source);

            ShadowReview continuity=baseline(task,source,chapter,version,ContinuityShadowService.CHECKER,"2026-01-02T00:00:00Z");
            ShadowReview plot=baseline(task,source,chapter,version,PlotForeshadowShadowService.CHECKER,"2026-01-01T00:00:00Z");
            novel.shadowReviews.add(continuity); novel.shadowReviews.add(plot);
            return novel;
        });
    }

    private ShadowReview baseline(Task task,SourceSnapshot source,Artifact artifact,Version version,
                                  String checker,String createdAt) {
        ShadowReview report=new ShadowReview(); report.taskId=task.id; report.sourceSnapshotId=source.id;
        report.artifactId=artifact.id; report.versionId=version.id; report.checker=checker;
        report.policyVersion="2026-09-24-v1"; report.status=ShadowReviewStatus.SUCCEEDED;
        report.review=clean(); report.createdAt=createdAt; report.finishedAt=createdAt;
        report.authorDecision=ShadowReviewDecision.USEFUL;
        return report;
    }

    private Review clean() { return new Review(true,List.of(),false,false,false,List.of()); }
}
