package com.novelforge;

import com.novelforge.generation.*;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.task.TaskService;
import com.novelforge.workflow.WorkflowService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:tasks;DB_CLOSE_DELAY=-1")
class TaskRecoveryTest {
    @Autowired NovelRepository repository;
    @Autowired WorkflowService workflow;
    @Autowired TaskService tasks;
    @MockitoBean ModelGateway model;
    @BeforeEach void mock() {
        when(model.ready()).thenReturn(true);when(model.mode()).thenReturn("http");
        when(model.outlineFoundation(any())).thenReturn(new ModelGateway.Generated(
                "人物与世界基础","人物、世界规则与结局约束材料","参谋材料摘要",List.of(),null));
        when(model.outlineContinuityReview(any(),any())).thenReturn(new Review(true,List.of(),false,false,false));
        when(model.outlinePlotReview(any(),any())).thenReturn(new Review(true,List.of(),false,false,false));
        when(model.review(any(),any())).thenReturn(new Review(true,List.of(),false,false,false));
    }
    Novel create() { return workflow.create("任务验证","可靠恢复",1000,""); }
    ModelGateway.Generated generated() { return OutlineTestFixtures.generated(1000,"完整主线与明确结局","摘要"); }
    @Test void successfulTaskPersistsFrozenSourceAndExplicitAgentDependencies() {
        when(model.generate(any())).thenReturn(generated());
        Novel n=create(); Task submitted=tasks.submit(n.id,Action.OUTLINE,null,"",Novel.uid(),n.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(n.id),submitted.id).status==TaskStatus.SUCCEEDED);

        Novel saved=repository.get(n.id); Task task=tasks.find(saved,submitted.id);
        SourceSnapshot source=saved.sourceSnapshots.stream().filter(item->item.id.equals(task.sourceSnapshotId)).findFirst().orElseThrow();
        List<AgentRun> runs=saved.agentRuns.stream().filter(run->run.taskId.equals(task.id)).toList();

        assertThat(source.novelRevision).isEqualTo(task.inputRevision);
        assertThat(source.contextHash).hasSize(64);
        assertThat(source.sourceVersionIds).isEqualTo(task.sourceVersionIds);
        assertThat(runs).hasSize(4);
        AgentRun foundation=runs.stream().filter(run->run.operation.equals("outline-foundation")).findFirst().orElseThrow();
        AgentRun writer=runs.stream().filter(run->run.operation.equals("generate")).findFirst().orElseThrow();
        AgentRun continuity=runs.stream().filter(run->run.operation.equals("outline-continuity-review")).findFirst().orElseThrow();
        AgentRun plot=runs.stream().filter(run->run.operation.equals("outline-plot-review")).findFirst().orElseThrow();
        assertThat(foundation.role).isEqualTo(AgentRole.CHARACTER_WORLD_DESIGNER.name());
        assertThat(foundation.status).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(writer.role).isEqualTo(AgentRole.STORY_ARCHITECT.name());
        assertThat(writer.status).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(writer.resultVersionId).isEqualTo(task.resultVersionId);
        assertThat(writer.upstreamAgentRunIds).containsExactly(foundation.id);
        assertThat(continuity.role).isEqualTo(AgentRole.CONTINUITY_AUDITOR.name());
        assertThat(plot.role).isEqualTo(AgentRole.PLOT_FORESHADOW_AUDITOR.name());
        assertThat(continuity.upstreamAgentRunIds).containsExactly(writer.id);
        assertThat(plot.upstreamAgentRunIds).containsExactly(writer.id);
        assertThat(continuity.inputVersionId).isEqualTo(writer.resultVersionId);
        assertThat(plot.inputVersionId).isEqualTo(writer.resultVersionId);
        assertThat(continuity.status).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(plot.status).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(saved.outlinePipelines).singleElement().satisfies(workspace->{
            assertThat(workspace.status).isEqualTo(OutlinePipelineStatus.SUCCEEDED);
            assertThat(workspace.maxModelCalls).isEqualTo(4);
            assertThat(workspace.modelCallsUsed).isEqualTo(4);
            assertThat(workspace.foundation).isNotNull();
            assertThat(workspace.deterministicReview).isNotNull();
            assertThat(workspace.artifactId).isEqualTo(task.resultArtifactId);
            assertThat(workspace.versionId).isEqualTo(task.resultVersionId);
            assertThat(workspace.continuityReview).isNotNull();
            assertThat(workspace.plotReview).isNotNull();
            assertThat(workspace.combinedReview).isNotNull();
        });
        verify(model,times(1)).generate(any());
        verify(model,times(1)).outlineFoundation(any());
        verify(model,times(1)).outlineContinuityReview(any(),any());
        verify(model,times(1)).outlinePlotReview(any(),any());
        verify(model,never()).review(any(),any());
    }
    @Test void failureCanBeRetriedExplicitly() {
        when(model.generate(any())).thenThrow(new IllegalStateException("fixture failure")).thenReturn(generated());
        Novel n=create();Task t=tasks.submit(n.id,Action.OUTLINE,null,"",Novel.uid(),0);
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(n.id),t.id).status==TaskStatus.FAILED);
        Novel failed=repository.get(n.id);
        assertThat(failed.artifacts).isEmpty();
        assertThat(failed.sourceSnapshots).hasSize(1);
        assertThat(failed.agentRuns).hasSize(2);
        assertThat(failed.agentRuns).extracting(run->run.status)
                .containsExactly(AgentRunStatus.SUCCEEDED,AgentRunStatus.FAILED);
        assertThat(failed.outlinePipelines).singleElement().satisfies(workspace->{
            assertThat(workspace.status).isEqualTo(OutlinePipelineStatus.FAILED);
            assertThat(workspace.foundation).isNotNull();
            assertThat(workspace.artifactId).isNull();
        });
        Task retry=tasks.retry(n.id,t.id,Novel.uid(),0);
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(n.id),retry.id).status==TaskStatus.SUCCEEDED);
        assertThat(repository.get(n.id).artifacts).hasSize(1);
    }
    @Test void failedReviewKeepsDraftAndRetryOnlyReviewsIt() {
        when(model.generate(any())).thenReturn(generated());
        when(model.outlineContinuityReview(any(),any())).thenThrow(new IllegalStateException("review unavailable"))
                .thenReturn(new Review(true,List.of(),false,false,false));
        Novel n=create(); Task first=tasks.submit(n.id,Action.OUTLINE,null,"保留主线要求",Novel.uid(),n.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(n.id),first.id).status==TaskStatus.FAILED);
        Novel saved=repository.get(n.id); Task failed=tasks.find(saved,first.id);
        assertThat(saved.artifacts).hasSize(1);
        assertThat(failed.resultArtifactId).isEqualTo(saved.artifacts.getFirst().id);
        assertThat(failed.resultVersionId).isEqualTo(saved.artifacts.getFirst().latest().id);
        assertThat(saved.artifacts.getFirst().latest().content).contains("故事核心","完整主线与明确结局","明确结局");
        assertThat(saved.artifacts.getFirst().latest().review).isNull();
        assertThat(failed.error).contains("候选内容已保存","不需要重新生成正文");

        Task retry=tasks.retry(n.id,first.id,Novel.uid(),saved.revision);
        assertThat(retry.action).isEqualTo(Action.REVIEW);
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(n.id),retry.id).status==TaskStatus.SUCCEEDED);
        assertThat(repository.get(n.id).artifacts.getFirst().latest().review.passed()).isTrue();
        verify(model,times(1)).generate(any());
        verify(model,times(2)).outlineContinuityReview(any(),any());
        verify(model,times(1)).outlinePlotReview(any(),any());
        verify(model,never()).review(any(),any());
    }
    @Test void localOutlineBudgetAllowsAnExactTargetWhenModelPassesIt() {
        ModelGateway.Generated exact=OutlineTestFixtures.generated(1000,"按一千字预算完成主线","总计一千字");
        when(model.generate(any())).thenReturn(exact);
        Novel n=create();Task task=tasks.submit(n.id,Action.OUTLINE,null,"",Novel.uid(),n.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(n.id),task.id).status==TaskStatus.SUCCEEDED);
        Version version=repository.get(n.id).artifacts.getFirst().latest();
        assertThat(version.review.passed()).isTrue();
        assertThat(version.review.issueDetails()).isEmpty();
        verify(model,times(1)).outlineContinuityReview(any(),any());
        verify(model,times(1)).outlinePlotReview(any(),any());
    }
    @Test void blockingOutlineFindingQueuesOneRepairAndRechecksOnlyImpactedAuditor() {
        var initialSpec=OutlineTestFixtures.validSpec(1000,"自动修订主线与明确结局");
        initialSpec.worldRules.getFirst().rule="旧世界规则";
        var repairedSpec=OutlineTestFixtures.validSpec(1000,"自动修订主线与明确结局");
        repairedSpec.worldRules.getFirst().rule="修订后的世界规则";
        var initial=new ModelGateway.Generated("全书大纲","由系统投影","初稿摘要",List.of(),null,initialSpec,List.of());
        var repaired=new ModelGateway.Generated("全书大纲","由系统投影","修订摘要",List.of(),null,repairedSpec,List.of());
        ReviewIssue conflict=new ReviewIssue("世界规则","规则与事件互相冲突",
                "旧世界规则无法支持当前事件","只调整世界规则，使其与事件一致","必须修正");
        when(model.generate(any())).thenReturn(initial,repaired);
        when(model.outlineContinuityReview(any(),any()))
                .thenReturn(new Review(false,List.of(conflict.text()),false,false,false,List.of(conflict)))
                .thenReturn(new Review(true,List.of(),false,false,false,List.of()));
        when(model.outlinePlotReview(any(),any())).thenReturn(new Review(true,List.of(),true,true,true,List.of()));

        Novel n=create(); Task first=tasks.submit(n.id,Action.OUTLINE,null,"",Novel.uid(),n.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->{
            Novel saved=repository.get(n.id);
            return saved.tasks.size()==2 && saved.tasks.stream().allMatch(task->task.status==TaskStatus.SUCCEEDED);
        });

        Novel saved=repository.get(n.id); Artifact outline=saved.artifacts.getFirst();
        Task automatic=saved.tasks.stream().filter(task->TaskService.OUTLINE_AUTO_REPAIR.equals(task.automationKind))
                .findFirst().orElseThrow();
        assertThat(tasks.find(saved,first.id).resultVersionId).isEqualTo(outline.versions.getFirst().id);
        assertThat(outline.versions).hasSize(2);
        assertThat(outline.approvedVersionId).isNull();
        assertThat(outline.versions.getFirst().review.passed()).isFalse();
        assertThat(outline.latest().review.passed()).isTrue();
        assertThat(outline.latest().outlineAutoRepairRound).isEqualTo(1);
        assertThat(automatic.action).isEqualTo(Action.REWRITE);
        assertThat(automatic.automationRound).isEqualTo(1);
        assertThat(saved.outlinePipelines).hasSize(2);
        OutlinePipelineWorkspace firstWorkspace=saved.outlinePipelines.getFirst();
        OutlinePipelineWorkspace repairedWorkspace=saved.outlinePipelines.getLast();
        assertThat(firstWorkspace.combinedReview.passed()).isFalse();
        assertThat(repairedWorkspace.resumedFromWorkspaceId).isEqualTo(firstWorkspace.id);
        assertThat(repairedWorkspace.status).isEqualTo(OutlinePipelineStatus.SUCCEEDED);
        assertThat(repairedWorkspace.modelCallsUsed).isEqualTo(2);
        assertThat(repairedWorkspace.changedSections).containsExactly("世界规则");
        assertThat(repairedWorkspace.continuityReviewRequired).isTrue();
        assertThat(repairedWorkspace.plotReviewRequired).isFalse();
        assertThat(repairedWorkspace.plotReviewReusedFromWorkspaceId).isEqualTo(firstWorkspace.id);
        assertThat(repairedWorkspace.continuityReviewReusedFromWorkspaceId).isNull();
        assertThat(repairedWorkspace.combinedReview.passed()).isTrue();
        assertThat(saved.agentRuns.stream().filter(run->automatic.id.equals(run.taskId))).hasSize(2);
        verify(model,times(2)).generate(any());
        verify(model,times(2)).outlineContinuityReview(any(),any());
        verify(model,times(1)).outlinePlotReview(any(),any());
    }
    @Test void automaticOutlineRepairNeverLoopsWhenBlockingFindingRemains() {
        var initialSpec=OutlineTestFixtures.validSpec(1000,"有界修订主线");
        initialSpec.worldRules.getFirst().rule="仍需修订的旧规则";
        var repairedSpec=OutlineTestFixtures.validSpec(1000,"有界修订主线");
        repairedSpec.worldRules.getFirst().rule="修订后仍被报告冲突的规则";
        when(model.generate(any())).thenReturn(
                new ModelGateway.Generated("大纲","由系统投影","初稿",List.of(),null,initialSpec,List.of()),
                new ModelGateway.Generated("大纲","由系统投影","修订稿",List.of(),null,repairedSpec,List.of()));
        ReviewIssue conflict=new ReviewIssue("世界规则","规则冲突仍未解决","当前规则仍不能支持事件",
                "停止自动循环并交给作者处理","必须修正");
        when(model.outlineContinuityReview(any(),any()))
                .thenReturn(new Review(false,List.of(conflict.text()),false,false,false,List.of(conflict)));
        when(model.outlinePlotReview(any(),any())).thenReturn(new Review(true,List.of(),true,true,true,List.of()));

        Novel n=create(); tasks.submit(n.id,Action.OUTLINE,null,"",Novel.uid(),n.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->{
            Novel saved=repository.get(n.id);
            return saved.tasks.size()==2 && saved.tasks.stream().allMatch(task->task.status==TaskStatus.SUCCEEDED);
        });
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .until(()->repository.get(n.id).tasks.size()==2);

        Novel saved=repository.get(n.id); Version latest=saved.artifacts.getFirst().latest();
        assertThat(saved.tasks).hasSize(2);
        assertThat(latest.outlineAutoRepairRound).isEqualTo(1);
        assertThat(latest.review.passed()).isFalse();
        assertThat(latest.review.issueDetails()).extracting(ReviewIssue::severity).contains("必须修正");
        assertThat(saved.artifacts.getFirst().approvedVersionId).isNull();
        verify(model,times(2)).generate(any());
        verify(model,times(2)).outlineContinuityReview(any(),any());
        verify(model,times(1)).outlinePlotReview(any(),any());
    }
    @Test void structuredManualOutlineEditPreservesVersionAndRechecksOnlyImpactedAuditor() {
        when(model.generate(any())).thenReturn(generated());
        Novel n=create(); Task generated=tasks.submit(n.id,Action.OUTLINE,null,"",Novel.uid(),n.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(n.id),generated.id).status==TaskStatus.SUCCEEDED);

        Novel initial=repository.get(n.id); Artifact outline=initial.artifacts.getFirst(); Version base=outline.latest();
        var editedSpec=OutlineTestFixtures.validSpec(1000,"完整主线与明确结局");
        editedSpec.worldRules.getFirst().rule="作者手动修改后的世界规则";
        Novel edited=workflow.editOutline(n.id,outline.id,new WorkflowService.OutlineEdit(base.id,base.title,
                base.summary,editedSpec,"作者调整世界规则",initial.revision));

        Artifact editedOutline=edited.artifacts.getFirst(); Version candidate=editedOutline.latest();
        assertThat(editedOutline.versions).hasSize(2);
        assertThat(candidate.baseVersionId).isEqualTo(base.id);
        assertThat(candidate.source).isEqualTo("USER");
        assertThat(candidate.content).contains("作者手动修改后的世界规则");
        assertThat(candidate.outlineSpecHash).hasSize(64).isNotEqualTo(base.outlineSpecHash);
        assertThat(candidate.review.passed()).isTrue();
        assertThat(candidate.reviewPolicyVersion).isEqualTo(com.novelforge.outline.OutlineStructureService.VERSION);

        Task review=tasks.submit(n.id,Action.REVIEW,outline.id,"",Novel.uid(),edited.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(n.id),review.id).status==TaskStatus.SUCCEEDED);
        Novel checked=repository.get(n.id); OutlinePipelineWorkspace workspace=checked.outlinePipelines.getLast();
        assertThat(workspace.changedSections).containsExactly("世界规则");
        assertThat(workspace.continuityReviewRequired).isTrue();
        assertThat(workspace.plotReviewRequired).isFalse();
        assertThat(workspace.plotReviewReusedFromWorkspaceId).isEqualTo(checked.outlinePipelines.getFirst().id);
        assertThat(workspace.modelCallsUsed).isEqualTo(1);
        assertThat(checked.artifacts.getFirst().latest().review.passed()).isTrue();
        assertThat(checked.artifacts.getFirst().approvedVersionId).isNull();
        verify(model,times(1)).generate(any());
        verify(model,times(2)).outlineContinuityReview(any(),any());
        verify(model,times(1)).outlinePlotReview(any(),any());
    }
    @Test void incompleteSummaryIsSavedAndBlocksConfirmationWithoutCallingReview() {
        ModelGateway.Generated incomplete=new ModelGateway.Generated("大纲","由系统投影","",List.of(),null,
                OutlineTestFixtures.validSpec(1000,"可恢复的完整主线"),List.of("内容摘要缺失或为空，请补全摘要后重新检查"));
        when(model.generate(any())).thenReturn(incomplete);
        Novel n=create(); Task task=tasks.submit(n.id,Action.OUTLINE,null,"",Novel.uid(),n.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(n.id),task.id).status==TaskStatus.SUCCEEDED);
        Novel saved=repository.get(n.id); Artifact draft=saved.artifacts.getFirst();
        assertThat(draft.latest().content).contains("可恢复的完整主线","明确结局");
        assertThat(draft.latest().summary).isEmpty();
        assertThat(draft.latest().draftIssues).isNotEmpty();
        assertThat(draft.latest().review.passed()).isFalse();
        assertThat(saved.outlinePipelines).singleElement().satisfies(workspace->{
            assertThat(workspace.status).isEqualTo(OutlinePipelineStatus.NEEDS_INPUT);
            assertThat(workspace.modelCallsUsed).isEqualTo(2);
            assertThat(workspace.artifactId).isEqualTo(draft.id);
            assertThat(workspace.versionId).isEqualTo(draft.latest().id);
            assertThat(workspace.continuityReview).isNull();
            assertThat(workspace.plotReview).isNull();
        });
        assertThatThrownBy(()->workflow.confirm(saved.id,draft.id,draft.latest().id,saved.revision,"作者接受"))
                .hasMessageContaining("摘要");
        verify(model,never()).outlineContinuityReview(any(),any());
        verify(model,never()).outlinePlotReview(any(),any());
    }
    @Test void summaryCanBeCompletedWithoutRegeneratingOrChangingBody() {
        var spec=OutlineTestFixtures.validSpec(1000,"必须原样保留的结构化主线");
        ModelGateway.Generated incomplete=new ModelGateway.Generated("大纲","由系统投影","",List.of(),null,
                spec,List.of("内容摘要缺失或为空，请补全摘要后重新检查"));
        ModelGateway.Generated completed=new ModelGateway.Generated("大纲","不会替换正文","补全后的准确摘要",List.of(),null,
                spec,List.of());
        when(model.generate(any())).thenReturn(incomplete).thenReturn(completed);
        Novel n=create(); Task first=tasks.submit(n.id,Action.OUTLINE,null,"",Novel.uid(),n.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(n.id),first.id).status==TaskStatus.SUCCEEDED);
        Novel pending=repository.get(n.id); Artifact artifact=pending.artifacts.getFirst();
        String bodyBefore=artifact.latest().content;

        Task summary=tasks.submitSummary(n.id,artifact.id,Novel.uid(),pending.revision);
        assertThat(summary.summaryOnly).isTrue();
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(n.id),summary.id).status==TaskStatus.SUCCEEDED);
        Novel saved=repository.get(n.id); Artifact repaired=saved.artifacts.getFirst();
        assertThat(repaired.versions).hasSize(2);
        assertThat(repaired.latest().content).isEqualTo(bodyBefore);
        assertThat(repaired.latest().summary).isEqualTo("补全后的准确摘要");
        assertThat(repaired.latest().draftIssues).isEmpty();
        assertThat(repaired.latest().review.passed()).isTrue();
        verify(model,times(2)).generate(any());
        verify(model,times(1)).outlineContinuityReview(any(),any());
        verify(model,times(1)).outlinePlotReview(any(),any());
        verify(model,never()).review(any(),any());
    }
    @Test void incompleteDraftCanBeManuallyEditedReviewedAndConfirmed() {
        ModelGateway.Generated incomplete=new ModelGateway.Generated("大纲","由系统投影","",List.of(),null,
                OutlineTestFixtures.validSpec(1000,"可查看并恢复的结构化主线"),List.of("摘要待补全"));
        when(model.generate(any())).thenReturn(incomplete);
        Novel n=create();Task generated=tasks.submit(n.id,Action.OUTLINE,null,"",Novel.uid(),n.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(n.id),generated.id).status==TaskStatus.SUCCEEDED);
        Novel pending=repository.get(n.id);Artifact artifact=pending.artifacts.getFirst();Version draft=artifact.latest();

        Novel edited=workflow.edit(n.id,artifact.id,new WorkflowService.Edit(draft.id,draft.title,draft.content,
                "作者手动补充的摘要",draft.facts,draft.plan,"只补充摘要",pending.revision));
        Version repaired=edited.artifacts.getFirst().latest();
        assertThat(repaired.content).isEqualTo(draft.content);
        assertThat(repaired.draftIssues).isEmpty();
        assertThat(repaired.review).isNull();
        Task review=tasks.submit(n.id,Action.REVIEW,artifact.id,"",Novel.uid(),edited.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(n.id),review.id).status==TaskStatus.SUCCEEDED);
        Novel checked=repository.get(n.id);
        Novel confirmed=workflow.confirm(n.id,artifact.id,checked.artifacts.getFirst().latest().id,checked.revision,null);
        assertThat(confirmed.artifacts.getFirst().clean()).isTrue();
        verify(model,times(1)).generate(any());
        verify(model,times(1)).outlineContinuityReview(any(),any());
        verify(model,times(1)).outlinePlotReview(any(),any());
        verify(model,never()).review(any(),any());
    }
    @Test void restartMarksOutstandingTasksInterruptedWithoutAutoCharging() {
        Novel n=create();Task t=new Task();t.action=Action.OUTLINE;t.status=TaskStatus.RUNNING;
        AgentRun run=new AgentRun();run.taskId=t.id;run.role=AgentRole.STORY_ARCHITECT.name();run.operation="generate";
        t.agentRunIds.add(run.id);
        Artifact draft=new Artifact();draft.kind=Kind.OUTLINE;
        Version version=new Version();version.title="待完善大纲";version.content="重启后必须存在的草稿正文";
        version.draftIssues=List.of("摘要待补全");draft.versions.add(version);
        t.resultArtifactId=draft.id;t.resultVersionId=version.id;t.stagedRevision=1;
        repository.update(n.id,x->{x.artifacts.add(draft);x.revision=1;x.tasks.add(t);x.agentRuns.add(run);return null;});
        tasks.recover();
        Novel recovered=repository.get(n.id);
        assertThat(tasks.find(recovered,t.id).status).isEqualTo(TaskStatus.INTERRUPTED);
        assertThat(recovered.agentRuns.getFirst().status).isEqualTo(AgentRunStatus.INTERRUPTED);
        assertThat(recovered.artifacts.getFirst().latest().content).isEqualTo("重启后必须存在的草稿正文");
        assertThat(recovered.artifacts.getFirst().latest().draftIssues).containsExactly("摘要待补全");
        verify(model,never()).generate(any());
    }
    @Test void changedInputMakesLateResultStale() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        when(model.generate(any())).thenAnswer(inv->{entered.countDown();release.await(5,TimeUnit.SECONDS);return generated();});
        Novel n=create();Task t=tasks.submit(n.id,Action.OUTLINE,null,"",Novel.uid(),0);
        assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
        repository.update(n.id,x->{x.revision++;return null;});release.countDown();
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(n.id),t.id).status==TaskStatus.STALE);
        assertThat(repository.get(n.id).artifacts).isEmpty();
    }
    @Test void cancellationDoesNotSaveLateOutputAndConcurrentSubmitIsRejected() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        when(model.generate(any())).thenAnswer(inv->{entered.countDown();release.await(5,TimeUnit.SECONDS);return generated();});
        Novel n=create();Task t=tasks.submit(n.id,Action.OUTLINE,null,"",Novel.uid(),0);
        assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(()->tasks.submit(n.id,Action.OUTLINE,null,"",Novel.uid(),0)).hasMessageContaining("执行中");
        tasks.cancel(n.id,t.id);release.countDown();
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(3)).until(()->repository.get(n.id).artifacts.isEmpty());
        Novel cancelled=repository.get(n.id);
        assertThat(tasks.find(cancelled,t.id).status).isEqualTo(TaskStatus.CANCELLED);
        assertThat(cancelled.agentRuns).hasSize(2);
        assertThat(cancelled.agentRuns).extracting(run->run.status)
                .containsExactly(AgentRunStatus.SUCCEEDED,AgentRunStatus.CANCELLED);
    }
}
