package com.novelforge;

import com.novelforge.conversation.ConversationService;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.task.TaskService;
import com.novelforge.workflow.WorkflowService;
import com.novelforge.workflow.WorkflowRules;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:conversation;DB_CLOSE_DELAY=-1")
class ConversationIntegrationTest {
    @Autowired WorkflowService workflow;
    @Autowired ConversationService conversations;
    @Autowired NovelRepository repository;
    @Autowired TaskService tasks;
    @Autowired WorkflowRules rules;

    @Test void acceptedDecisionCreatesOrdinaryOutlineTaskWithoutLeakingRawChat() {
        Novel novel=workflow.create("雨夜失踪者","一名记者追查雨夜失踪案",1000,"主线完整并有明确结局");
        ConversationSession session=conversations.startOutline(novel.id,novel.revision);
        ConversationSession replied=conversations.send(novel.id,session.id,"RAW_CHAT_SHOULD_NOT_ENTER 正在讨论，还不能修改作品");

        assertThat(repository.get(novel.id).artifacts).isEmpty();
        assertThat(repository.get(novel.id).revision).isZero();
        assertThat(replied.messages).hasSize(2);
        assertThat(replied.decisions).singleElement().satisfies(decision->
                assertThat(decision.status).isEqualTo(DecisionStatus.PROPOSED));
        assertThat(replied.proposals).isEmpty();

        ConversationDecision decision=replied.decisions.getFirst();
        conversations.decide(novel.id,session.id,decision.id,DecisionStatus.ACCEPTED);
        ConversationSession generated=conversations.send(novel.id,session.id,"[演示立即生成] 现在按已经确定的要求立即生成大纲");
        ActionProposal proposal=generated.proposals.getFirst();
        assertThat(proposal.automaticExecution).isTrue();
        assertThat(proposal.status).isEqualTo(ProposalStatus.EXECUTED);
        Task task=tasks.find(repository.get(novel.id),proposal.taskId);
        await().atMost(Duration.ofSeconds(10)).until(()->{
            TaskStatus status=tasks.find(repository.get(novel.id),task.id).status;
            return status!=TaskStatus.QUEUED&&status!=TaskStatus.RUNNING;
        });

        Novel saved=repository.get(novel.id);
        Task finished=tasks.find(saved,task.id);
        assertThat(finished.status).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(finished.conversationBriefId).isNotBlank();
        assertThat(finished.acceptedDecisionIds).contains(decision.id,proposal.requestedByUserMessageId);
        SourceSnapshot source=saved.sourceSnapshots.stream().filter(item->item.id.equals(finished.sourceSnapshotId))
                .findFirst().orElseThrow();
        assertThat(source.conversationBriefHash).isEqualTo(finished.conversationBriefHash);
        assertThat(source.contextJson).contains("conversationBrief","AUTHOR_ACCEPTED_INTENT_NOT_CANON")
                .contains("必须保留").doesNotContain("RAW_CHAT_SHOULD_NOT_ENTER");
        assertThat(saved.artifacts).singleElement().satisfies(outline->
                assertThat(outline.approvedVersionId).isNull());
        assertThat(saved.conversationSessions.getFirst().proposals.getFirst().status)
                .isEqualTo(ProposalStatus.EXECUTED);
    }

    @Test void staleConversationCannotWriteAfterFormalVersionChanges() {
        Novel novel=workflow.create("旧城迷踪","从一桩失踪案开始",1000,"");
        ConversationSession session=conversations.startOutline(novel.id,novel.revision);
        conversations.send(novel.id,session.id,"先讨论大纲方向");
        Task direct=tasks.submit(novel.id,Action.OUTLINE,null,"",Novel.uid(),novel.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(novel.id),direct.id).status==TaskStatus.SUCCEEDED);

        long changedRevision=repository.get(novel.id).revision;
        assertThat(changedRevision).isGreaterThan(novel.revision);
        assertThatThrownBy(()->conversations.send(novel.id,session.id,"继续讨论"))
                .hasMessageContaining("已经变化");
    }

    @Test void newConversationHasIsolatedMemoryAndEachThreadCanContinueAcrossVersions() {
        Novel novel=workflow.create("隔离测试","验证多个创作对话互不串线",1000,"");
        ConversationSession first=conversations.startOutline(novel.id,novel.revision,null,true);
        ConversationSession firstReplied=conversations.send(novel.id,first.id,"FIRST_THREAD_MEMORY 讨论第一种主线");
        conversations.decide(novel.id,first.id,firstReplied.decisions.getFirst().id,DecisionStatus.ACCEPTED);

        ConversationSession second=conversations.startOutline(novel.id,novel.revision,null,true);
        assertThat(second.threadId).isNotEqualTo(first.threadId);
        assertThat(second.messages).isEmpty();
        assertThat(second.decisions).isEmpty();
        assertThat(second.proposals).isEmpty();
        assertThat(second.projectUpdates).isEmpty();
        conversations.send(novel.id,second.id,"SECOND_THREAD_MEMORY 讨论另一种主线");

        Task direct=tasks.submit(novel.id,Action.OUTLINE,null,"",Novel.uid(),novel.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(novel.id),direct.id).status==TaskStatus.SUCCEEDED);
        Novel changed=repository.get(novel.id);
        ConversationSession continued=conversations.startOutline(novel.id,changed.revision,first.threadId,false);

        assertThat(continued.threadId).isEqualTo(first.threadId);
        assertThat(continued.previousSessionId).isEqualTo(first.id);
        assertThat(continued.messages).extracting(message->message.content)
                .anyMatch(text->text.contains("FIRST_THREAD_MEMORY"))
                .noneMatch(text->text.contains("SECOND_THREAD_MEMORY"));
        assertThat(continued.decisions).singleElement().satisfies(decision->{
            assertThat(decision.status).isEqualTo(DecisionStatus.ACCEPTED);
            assertThat(decision.text).isEqualTo(firstReplied.decisions.getFirst().text);
        });
    }

    @Test void legacyConversationWithoutThreadIdGetsStableIdentityAndCanResume() {
        Novel novel=workflow.create("旧对话迁移","验证升级后仍能发送消息",1000,"");
        ConversationSession created=conversations.startOutline(novel.id,novel.revision);
        repository.update(novel.id,saved->{saved.conversationSessions.getFirst().threadId=null;return null;});

        ConversationSession firstRead=conversations.list(novel.id).getFirst();
        ConversationSession secondRead=conversations.list(novel.id).getFirst();
        assertThat(firstRead.threadId).isEqualTo(created.id).isEqualTo(secondRead.threadId);

        ConversationSession resumed=conversations.startOutline(novel.id,novel.revision,firstRead.threadId,false);
        assertThat(resumed.id).isEqualTo(created.id);
        assertThat(repository.get(novel.id).conversationSessions.getFirst().threadId).isEqualTo(created.id);
    }

    @Test void dialogueMessageRequestIsIdempotentAndPersistsTurnState() {
        Novel novel=workflow.create("消息幂等测试","重复提交不能产生重复对话",1000,"");
        ConversationSession session=conversations.startOutline(novel.id,novel.revision);
        String requestKey=Novel.uid();

        ConversationSession first=conversations.send(novel.id,session.id,"讨论同一条消息",requestKey);
        ConversationSession repeated=conversations.send(novel.id,session.id,"讨论同一条消息",requestKey);

        assertThat(first.messages).hasSize(2);
        assertThat(repeated.messages).hasSize(2);
        assertThat(repeated.turns).singleElement().satisfies(turn->{
            assertThat(turn.requestKey).isEqualTo(requestKey);
            assertThat(turn.status).isEqualTo(ConversationTurnStatus.SUCCEEDED);
            assertThat(turn.userMessageId).isNotBlank();
            assertThat(turn.assistantMessageId).isNotBlank();
            assertThat(turn.finishedAt).isNotBlank();
        });
        assertThatThrownBy(()->conversations.send(novel.id,session.id,"换成另一条内容",requestKey))
                .hasMessageContaining("同一消息请求");
    }

    @Test void explicitGenerateCommandStartsBoundedTaskEvenWhenModelOmitsActionProposal() {
        Novel novel=workflow.create("直接执行测试","验证用户明确命令",1000,"");
        ConversationSession session=conversations.startOutline(novel.id,novel.revision);
        ConversationSession replied=conversations.send(novel.id,session.id,"直接生成大纲");
        assertThat(replied.proposals).singleElement().satisfies(proposal->{
            assertThat(proposal.automaticExecution).isTrue();
            assertThat(proposal.status).isEqualTo(ProposalStatus.EXECUTED);
            assertThat(proposal.taskId).isNotBlank();
        });
        assertThat(repository.get(novel.id).tasks).singleElement().satisfies(task->{
            assertThat(task.conversationBriefId).isNotBlank();
            assertThat(task.progressStage).isNotBlank();
        });
    }

    @Test void projectBriefProposalOnlyChangesMetadataAfterAuthorAcceptsAndAppliesIt() {
        Novel novel=workflow.create("消失在雨夜的人","记者追查一宗雨夜失踪案",1000,"结局必须明确");
        ConversationSession session=conversations.startOutline(novel.id,novel.revision);
        ConversationSession replied=conversations.send(novel.id,session.id,"[演示修改资料] 请把书名改得更简洁");

        Novel untouched=repository.get(novel.id);
        assertThat(untouched.title).isEqualTo("消失在雨夜的人");
        assertThat(untouched.revision).isZero();
        ProjectUpdateProposal update=replied.projectUpdates.getFirst();
        assertThat(update.field).isEqualTo(ProjectField.TITLE);
        assertThat(update.previousValue).isEqualTo("消失在雨夜的人");
        assertThat(update.proposedValue).isEqualTo("雨夜失踪者");
        assertThat(update.status).isEqualTo(ProjectUpdateStatus.PROPOSED);
        assertThatThrownBy(()->conversations.applyProjectUpdates(novel.id,session.id,novel.revision))
                .hasMessageContaining("先采纳至少一项");

        conversations.decideProjectUpdate(novel.id,session.id,update.id,ProjectUpdateStatus.ACCEPTED);
        ConversationService.ProjectApplyResult result=conversations.applyProjectUpdates(novel.id,session.id,novel.revision);
        Novel saved=repository.get(novel.id);

        assertThat(saved.title).isEqualTo("雨夜失踪者");
        assertThat(saved.revision).isEqualTo(1);
        assertThat(saved.projectBriefChanges).singleElement().satisfies(change->{
            assertThat(change.previousRevision).isZero();
            assertThat(change.newRevision).isEqualTo(1);
            assertThat(change.fields).singleElement().satisfies(field->{
                assertThat(field.field()).isEqualTo(ProjectField.TITLE);
                assertThat(field.previousValue()).isEqualTo("消失在雨夜的人");
                assertThat(field.newValue()).isEqualTo("雨夜失踪者");
            });
        });
        assertThat(saved.conversationSessions.getFirst().projectUpdates.getFirst().status)
                .isEqualTo(ProjectUpdateStatus.APPLIED);
        assertThat(saved.conversationSessions.getFirst().proposals).isEmpty();
        assertThat(result.session().id).isNotEqualTo(session.id);
        assertThat(result.session().baseRevision).isEqualTo(1);
        assertThat(result.session().decisions).isEmpty();
    }

    @Test void applyingProjectBriefChangeMarksExistingOutlineForRevisionAndCarriesAcceptedDecisionsForward() {
        Novel novel=workflow.create("旧标题","旧简介",1000,"");
        Task outlineTask=tasks.submit(novel.id,Action.OUTLINE,null,"",Novel.uid(),novel.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(novel.id),outlineTask.id).status==TaskStatus.SUCCEEDED);
        Novel withOutline=repository.get(novel.id);
        ConversationSession session=conversations.startOutline(novel.id,withOutline.revision);
        ConversationSession replied=conversations.send(novel.id,session.id,"[演示修改资料] 修改书名并保留案件主线");
        conversations.decide(novel.id,session.id,replied.decisions.getFirst().id,DecisionStatus.ACCEPTED);
        conversations.decideProjectUpdate(novel.id,session.id,replied.projectUpdates.getFirst().id,ProjectUpdateStatus.ACCEPTED);

        ConversationService.ProjectApplyResult result=conversations.applyProjectUpdates(novel.id,session.id,withOutline.revision);
        Novel saved=repository.get(novel.id);
        Artifact outline=saved.artifacts.getFirst();
        assertThat(outline.kind).isEqualTo(Kind.OUTLINE);
        assertThat(outline.needsRevision).isTrue();
        assertThat(saved.changes).anySatisfy(change->assertThat(change.sourceArtifactId).isEqualTo("PROJECT_BRIEF"));
        assertThat(result.session().decisions).singleElement().satisfies(decision->{
            assertThat(decision.status).isEqualTo(DecisionStatus.ACCEPTED);
            assertThat(decision.text).contains("书名、简介");
        });
        assertThat(result.session().targetArtifactId).isEqualTo(outline.id);
        assertThat(result.session().baseVersionId).isEqualTo(outline.latest().id);
    }

    @Test void allSupportedProjectFieldsApplyAtomicallyAndTargetWordsUseBudgetAudit() {
        Novel novel=workflow.create("旧标题","旧简介",1000,"旧要求");
        ConversationSession session=conversations.startOutline(novel.id,novel.revision);
        ConversationSession replied=conversations.send(novel.id,session.id,"[演示修改资料] 同时更新项目资料");
        assertThat(replied.projectUpdates).extracting(item->item.field)
                .containsExactly(ProjectField.TITLE,ProjectField.SYNOPSIS,ProjectField.REQUIREMENTS,ProjectField.TARGET_WORDS);
        for (ProjectUpdateProposal update:replied.projectUpdates) {
            conversations.decideProjectUpdate(novel.id,session.id,update.id,ProjectUpdateStatus.ACCEPTED);
        }

        conversations.applyProjectUpdates(novel.id,session.id,novel.revision);
        Novel saved=repository.get(novel.id);
        assertThat(saved.title).isEqualTo("雨夜失踪者");
        assertThat(saved.synopsis).contains("遗忘的一晚");
        assertThat(saved.requirements).contains("线索公平");
        assertThat(saved.targetWords).isEqualTo(900);
        assertThat(saved.approvedMaxWords).isEqualTo(novel.approvedMaxWords);
        assertThat(saved.revision).isEqualTo(1);
        assertThat(saved.projectBriefChanges).singleElement().satisfies(change->assertThat(change.fields).hasSize(4));
        assertThat(saved.budgetChanges).singleElement().satisfies(change->{
            assertThat(change.previousTarget()).isEqualTo(1000);
            assertThat(change.newTarget()).isEqualTo(900);
            assertThat(change.previousMax()).isEqualTo(change.newMax());
        });
    }

    @Test void generatedCandidateCanBeDismissedWithoutDeletingHistoryAndConversationCanContinue() {
        Novel novel=workflow.create("待定故事","测试生成后放弃",1000,"");
        ConversationSession session=conversations.startOutline(novel.id,novel.revision);
        ConversationSession generated=conversations.send(novel.id,session.id,"[演示立即生成] 立即生成大纲");
        ActionProposal proposal=generated.proposals.getFirst();
        await().atMost(Duration.ofSeconds(10)).until(()->{
            TaskStatus status=tasks.find(repository.get(novel.id),proposal.taskId).status;
            return status!=TaskStatus.QUEUED&&status!=TaskStatus.RUNNING;
        });
        Novel withCandidate=repository.get(novel.id);
        Task finished=tasks.find(withCandidate,proposal.taskId);
        Artifact outline=rules.artifact(withCandidate,finished.resultArtifactId);
        Version candidate=outline.latest();

        workflow.dismissCandidate(novel.id,outline.id,candidate.id,withCandidate.revision);
        Novel dismissed=repository.get(novel.id);
        Artifact saved=rules.artifact(dismissed,outline.id);
        assertThat(saved.latest()).isNull();
        assertThat(saved.versions).singleElement().satisfies(version->{
            assertThat(version.dismissed).isTrue();
            assertThat(version.dismissedAt).isNotBlank();
        });
        assertThat(rules.nextAction(dismissed)).isEqualTo("OUTLINE");

        ConversationSession continued=conversations.startOutline(novel.id,dismissed.revision);
        assertThat(continued.id).isNotEqualTo(session.id);
        assertThat(continued.threadId).isEqualTo(session.threadId);
        assertThat(continued.messages).anySatisfy(message->assertThat(message.content).contains("立即生成大纲"));
        assertThat(continued.messages.getLast().content).contains("最新内容继续");
    }
}
