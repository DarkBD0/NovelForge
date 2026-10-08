package com.novelforge;

import com.novelforge.generation.AgentRole;
import com.novelforge.generation.AgentContextPolicy;
import com.novelforge.generation.ContinuityShadowService;
import com.novelforge.generation.ModelGateway;
import com.novelforge.generation.SourceSnapshotFactory;
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
        "spring.datasource.url=jdbc:h2:mem:continuity-shadow;DB_CLOSE_DELAY=-1",
        "novelforge.experiments.continuity-shadow-enabled=true"
})
class ContinuityShadowIntegrationTest {
    @Autowired NovelRepository repository;
    @Autowired WorkflowService workflow;
    @Autowired TaskService tasks;
    @Autowired ContinuityShadowService shadows;
    @Autowired SourceSnapshotFactory snapshots;
    @MockitoBean ModelGateway model;

    @BeforeEach void mockModel() {
        when(model.ready()).thenReturn(true);
        when(model.mode()).thenReturn("http");
        when(model.review(any(),any())).thenReturn(new Review(true,List.of(),false,false,false,List.of()));
        when(model.extractState(any(),any())).thenReturn(new ModelGateway.StateExtraction(List.of(
                new ModelGateway.ExtractedState("event_enter_room","EVENT","人物进入房间","ACTIVE",
                        List.of("他推门进入房间。")))));
    }

    @Test void persistsIndependentReportWithoutBlockingConfirmationOrRunningTwice() {
        ReviewIssue issue=new ReviewIssue("CONTINUITY:OBJECT_LOCATION:HIGH",
                "第一章中以“他推门”开头的位置","人物位置与已确认内容冲突",
                "冲突对象：人物｜冲突属性：位置｜原状态时间：当前时点｜候选状态时间：当前时点｜"
                        +"同一时点：“是”｜推进授权：“无”｜候选原文：“他推门进入房间”｜已确认依据：“他仍在海港”",
                "把候选位置改为海港","必须修正");
        when(model.continuityReview(any(),any())).thenReturn(new Review(false,List.of(issue.text()),false,false,false,List.of(issue)));
        Novel novel=pendingChapter("影子报告验证");
        Artifact chapter=novel.artifacts.getFirst();

        Task submitted=tasks.submit(novel.id,Action.REVIEW,chapter.id,"",Novel.uid(),novel.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->{
            Novel saved=repository.get(novel.id);
            return tasks.find(saved,submitted.id).status==TaskStatus.SUCCEEDED
                    && saved.shadowReviews.size()==1
                    && saved.shadowReviews.getFirst().status==ShadowReviewStatus.SUCCEEDED;
        });

        Novel saved=repository.get(novel.id); Task task=tasks.find(saved,submitted.id);
        Version version=saved.artifacts.getFirst().latest(); ShadowReview shadow=saved.shadowReviews.getFirst();
        assertThat(version.review.passed()).isTrue();
        assertThat(shadow.review.passed()).isTrue();
        assertThat(shadow.review.issueDetails()).singleElement().satisfies(savedIssue->{
            assertThat(savedIssue.severity()).isEqualTo("作者决定");
            assertThat(savedIssue.suggestion()).contains("专业检查已将其降为作者决定");
        });
        assertThat(shadow.policyVersion).isEqualTo(ContinuityShadowService.POLICY_VERSION);
        assertThat(saved.agentRuns).hasSize(3);
        assertThat(saved.agentRuns).anySatisfy(run->{
            assertThat(run.role).isEqualTo(AgentRole.CONTINUITY_AUDITOR.name());
            assertThat(run.operation).isEqualTo("shadow-continuity-review");
            assertThat(run.inputVersionId).isEqualTo(version.id);
            assertThat(run.status).isEqualTo(AgentRunStatus.SUCCEEDED);
            assertThat(run.contextPolicyVersion).isEqualTo(AgentContextPolicy.VERSION);
            assertThat(run.contextHash).hasSize(64);
            assertThat(run.contextJson).contains("\"role\":\"CONTINUITY_AUDITOR\"");
        });

        SourceSnapshot source=saved.sourceSnapshots.stream().filter(item->item.id.equals(task.sourceSnapshotId)).findFirst().orElseThrow();
        ModelGateway.Generated candidate=new ModelGateway.Generated(version.title,version.content,version.summary,version.facts,version.plan);
        shadows.run(saved.id,task.id,source.id,saved.artifacts.getFirst().id,version.id,
                new ModelGateway.Request(Action.REVIEW,saved,saved.artifacts.getFirst(),snapshots.restore(source),""),candidate,List.of());
        assertThat(repository.get(saved.id).shadowReviews).hasSize(1);
        verify(model,times(1)).continuityReview(any(),any());

        Novel confirmed=workflow.confirm(saved.id,saved.artifacts.getFirst().id,version.id,saved.revision,null);
        assertThat(confirmed.artifacts.getFirst().clean()).isTrue();
    }

    @Test void checkerFailureIsRecordedButFormalReviewAndConfirmationStillSucceed() {
        when(model.continuityReview(any(),any())).thenThrow(new IllegalStateException("shadow unavailable"));
        Novel novel=pendingChapter("影子失败隔离"); Artifact chapter=novel.artifacts.getFirst();

        Task submitted=tasks.submit(novel.id,Action.REVIEW,chapter.id,"",Novel.uid(),novel.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->{
            Novel saved=repository.get(novel.id);
            return tasks.find(saved,submitted.id).status==TaskStatus.SUCCEEDED
                    && !saved.shadowReviews.isEmpty()
                    && saved.shadowReviews.getFirst().status==ShadowReviewStatus.FAILED;
        });

        Novel saved=repository.get(novel.id); Version version=saved.artifacts.getFirst().latest();
        assertThat(version.review.passed()).isTrue();
        assertThat(saved.shadowReviews.getFirst().error).contains("正式检查结果和候选内容不受影响");
        Novel confirmed=workflow.confirm(saved.id,saved.artifacts.getFirst().id,version.id,saved.revision,null);
        assertThat(confirmed.artifacts.getFirst().clean()).isTrue();
    }

    @Test void restartInterruptsAnOrphanedShadowRunWithoutChangingTheCompletedTask() {
        Novel novel=pendingChapter("影子恢复验证");
        repository.update(novel.id,n->{
            Task task=new Task(); task.action=Action.REVIEW; task.status=TaskStatus.SUCCEEDED;
            AgentRun run=new AgentRun(); run.taskId=task.id; run.role=AgentRole.CONTINUITY_AUDITOR.name();
            run.operation="shadow-continuity-review"; task.agentRunIds.add(run.id);
            ShadowReview shadow=new ShadowReview(); shadow.taskId=task.id; shadow.agentRunId=run.id;
            shadow.artifactId=n.artifacts.getFirst().id; shadow.versionId=n.artifacts.getFirst().latest().id;
            shadow.checker=ContinuityShadowService.CHECKER;
            n.tasks.add(task); n.agentRuns.add(run); n.shadowReviews.add(shadow); return null;
        });

        tasks.recover();

        Novel recovered=repository.get(novel.id);
        assertThat(recovered.tasks.getFirst().status).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(recovered.agentRuns.getFirst().status).isEqualTo(AgentRunStatus.INTERRUPTED);
        assertThat(recovered.shadowReviews.getFirst().status).isEqualTo(ShadowReviewStatus.INTERRUPTED);
        assertThat(recovered.artifacts.getFirst().latest().content).isEqualTo("他推门进入房间。");
    }

    private Novel pendingChapter(String title) {
        Novel novel=workflow.create(title,"验证连续性影子检查",1000,"");
        return repository.update(novel.id,n->{
            n.autoStyleEnabled=false;
            Artifact chapter=new Artifact(); chapter.kind=Kind.CHAPTER; chapter.chapterNumber=1;
            Version version=new Version(); version.title="第一章"; version.content="他推门进入房间。";
            version.summary="人物进入房间"; version.facts=List.of(); version.basedOnRevision=n.revision;
            chapter.versions.add(version); n.artifacts.add(chapter); n.revision++;
            return n;
        });
    }
}
