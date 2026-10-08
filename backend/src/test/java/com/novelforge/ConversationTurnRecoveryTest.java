package com.novelforge;

import com.novelforge.conversation.ConversationService;
import com.novelforge.generation.ModelGateway;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.shared.Problem;
import com.novelforge.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:conversation-turns;DB_CLOSE_DELAY=-1")
class ConversationTurnRecoveryTest {
    @Autowired WorkflowService workflow;
    @Autowired ConversationService conversations;
    @Autowired NovelRepository repository;
    @MockitoBean ModelGateway model;

    @BeforeEach void defaults() {
        when(model.ready()).thenReturn(true);
        when(model.mode()).thenReturn("http");
    }

    @Test void failedReplyKeepsUserMessageAndCanRetryWithoutDuplicatingIt() {
        Novel novel=workflow.create("对话恢复测试","模型失败后可以只重试回复",1000,"");
        ConversationSession session=conversations.startOutline(novel.id,novel.revision);
        when(model.dialogue(any(),any())).thenThrow(new Problem(502,"模拟对话失败"));

        assertThatThrownBy(()->conversations.send(novel.id,session.id,"请帮我分析主线",Novel.uid()))
                .hasMessageContaining("模拟对话失败");
        ConversationSession failed=conversations.list(novel.id).getFirst();
        assertThat(failed.messages).singleElement().satisfies(message->{
            assertThat(message.role).isEqualTo(ConversationRole.USER);
            assertThat(message.content).isEqualTo("请帮我分析主线");
        });
        ConversationTurn turn=failed.turns.getFirst();
        assertThat(turn.status).isEqualTo(ConversationTurnStatus.FAILED);
        assertThat(turn.error).contains("模拟对话失败");

        doReturn(new ModelGateway.DialogueResponse(
                "可以先确定主角的核心选择。",List.of(),List.of(),null)).when(model).dialogue(any(),any());
        ConversationSession retried=conversations.retry(novel.id,session.id,turn.id);

        assertThat(retried.messages).hasSize(2);
        assertThat(retried.messages).extracting(message->message.role)
                .containsExactly(ConversationRole.USER,ConversationRole.ASSISTANT);
        assertThat(retried.turns).singleElement().satisfies(saved->{
            assertThat(saved.status).isEqualTo(ConversationTurnStatus.SUCCEEDED);
            assertThat(saved.attempt).isEqualTo(2);
            assertThat(saved.assistantMessageId).isNotBlank();
            assertThat(saved.error).isNull();
        });
    }

    @Test void serviceRecoveryMarksRunningTurnInterruptedAndRetainsDraftMessage() {
        Novel novel=workflow.create("重启恢复测试","服务重启不丢用户消息",1000,"");
        ConversationSession session=conversations.startOutline(novel.id,novel.revision);
        repository.update(novel.id,saved->{
            ConversationSession current=saved.conversationSessions.getFirst();
            ConversationMessage message=new ConversationMessage();
            message.role=ConversationRole.USER; message.content="尚未完成的消息";
            current.messages.add(message);
            ConversationTurn turn=new ConversationTurn(); turn.requestKey=Novel.uid(); turn.userMessageId=message.id;
            current.turns.add(turn); return null;
        });

        conversations.recoverInterruptedTurns();

        ConversationSession recovered=conversations.list(novel.id).getFirst();
        assertThat(recovered.messages).extracting(message->message.content).contains("尚未完成的消息");
        assertThat(recovered.turns).singleElement().satisfies(turn->{
            assertThat(turn.status).isEqualTo(ConversationTurnStatus.INTERRUPTED);
            assertThat(turn.error).contains("服务重启");
            assertThat(turn.finishedAt).isNotBlank();
        });
    }

    @Test void authorCanCancelOnlyTheRunningReplyAndKeepTheUserMessage() throws Exception {
        Novel novel=workflow.create("取消对话测试","停止时不丢用户消息",1000,"");
        ConversationSession session=conversations.startOutline(novel.id,novel.revision);
        CountDownLatch started=new CountDownLatch(1);
        when(model.dialogue(any(),any())).thenAnswer(call->{
            ModelGateway.DialogueStream stream=call.getArgument(1);stream.stage("GENERATING");started.countDown();
            while(!stream.cancelled()) Thread.sleep(5);
            throw new Problem(409,"[CANCELLED] 本次对话已停止");
        });
        CompletableFuture<Throwable> pending=CompletableFuture.supplyAsync(()->{
            try { conversations.send(novel.id,session.id,"请分析主线",Novel.uid()); return null; }
            catch(Throwable error) { return error; }
        });
        assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
        ConversationTurn running=conversations.list(novel.id).getFirst().turns.getFirst();

        ConversationSession cancelled=conversations.cancel(novel.id,session.id,running.id);

        assertThat(pending.get(2,TimeUnit.SECONDS)).isInstanceOf(Problem.class);
        assertThat(cancelled.messages).singleElement().satisfies(message->assertThat(message.content).isEqualTo("请分析主线"));
        assertThat(conversations.list(novel.id).getFirst().turns).singleElement().satisfies(turn->{
            assertThat(turn.status).isEqualTo(ConversationTurnStatus.INTERRUPTED);
            assertThat(turn.error).contains("已由你停止");
        });
    }
}
