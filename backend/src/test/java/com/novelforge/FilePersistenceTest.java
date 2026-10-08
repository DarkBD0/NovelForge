package com.novelforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.AgentRun;
import com.novelforge.novel.Novel.AgentRunStatus;
import com.novelforge.novel.Novel.SourceSnapshot;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Version;
import com.novelforge.novel.Novel.ConversationSession;
import com.novelforge.novel.Novel.ConversationMessage;
import com.novelforge.novel.Novel.ConversationRole;
import com.novelforge.novel.Novel.ConversationDecision;
import com.novelforge.novel.Novel.DecisionType;
import com.novelforge.novel.Novel.DecisionStatus;
import com.novelforge.novel.Novel.ProjectBriefChange;
import com.novelforge.novel.Novel.ProjectField;
import com.novelforge.novel.Novel.ProjectFieldChange;
import com.novelforge.novel.Novel.ProjectUpdateProposal;
import com.novelforge.novel.Novel.ProjectUpdateStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class FilePersistenceTest {
    Path directory;
    @BeforeEach void createWorkspaceTempDirectory() throws Exception {
        Path root=Path.of("..",".test-tmp").toAbsolutePath().normalize();
        directory=root.resolve("file-persistence-"+UUID.randomUUID()).normalize();
        assertThat(directory.startsWith(root)).isTrue();
        Files.createDirectories(directory);
    }
    @AfterEach void removeWorkspaceTempDirectory() throws Exception {
        if (directory==null || !Files.exists(directory)) return;
        try (var files=Files.list(directory)) {
            for (Path file:files.toList()) Files.deleteIfExists(file);
        }
        Files.deleteIfExists(directory);
    }
    @Test void reopensSameFileAfterDatabaseShutdown() {
        String url="jdbc:h2:file:"+directory.resolve("novel").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE";
        var first=new DriverManagerDataSource(url,"sa","");
        JdbcTemplate jdbc=new JdbcTemplate(first);
        jdbc.execute("CREATE TABLE novels(id VARCHAR(36) PRIMARY KEY, document CLOB NOT NULL, created_at VARCHAR(40) NOT NULL)");
        var repo=new NovelRepository(jdbc,new ObjectMapper(),new TransactionTemplate(new DataSourceTransactionManager(first)));
        Novel n=new Novel();n.title="重启后继续的故事";
        Artifact draft=new Artifact();draft.kind=Kind.CHAPTER;draft.chapterNumber=2;
        Version version=new Version();version.title="第二章草稿";version.content="已生成但尚未完成检查的正文";
        version.draftIssues=List.of("摘要待补全");draft.versions.add(version);n.artifacts.add(draft);
        SourceSnapshot snapshot=new SourceSnapshot();snapshot.taskId="task-1";snapshot.novelRevision=3;
        snapshot.contextJson="{\"title\":\"重启后继续的故事\"}";snapshot.contextHash="hash";
        snapshot.sourceVersionIds=List.of("source-v1");n.sourceSnapshots.add(snapshot);
        AgentRun run=new AgentRun();run.taskId="task-1";run.sourceSnapshotId=snapshot.id;
        run.role="CHAPTER_WRITER";run.operation="generate";run.status=AgentRunStatus.SUCCEEDED;
        run.resultVersionId=version.id;n.agentRuns.add(run);
        ConversationSession conversation=new ConversationSession();conversation.baseRevision=3;
        conversation.threadId="outline-thread-1";conversation.threadTitle="雨夜主线讨论";
        ConversationMessage message=new ConversationMessage();message.role=ConversationRole.USER;message.content="重启后仍应保留的讨论";
        ConversationDecision decision=new ConversationDecision();decision.sourceMessageId=message.id;
        decision.type=DecisionType.MUST_KEEP;decision.status=DecisionStatus.ACCEPTED;decision.text="保留雨夜线索";
        ProjectUpdateProposal update=new ProjectUpdateProposal();update.sourceMessageId=message.id;
        update.field=ProjectField.TITLE;update.previousValue="旧书名";update.proposedValue="重启后继续的故事";
        update.status=ProjectUpdateStatus.APPLIED;update.baseRevision=2;
        conversation.messages.add(message);conversation.decisions.add(decision);conversation.projectUpdates.add(update);
        n.conversationSessions.add(conversation);
        ProjectBriefChange briefChange=new ProjectBriefChange();briefChange.conversationSessionId=conversation.id;
        briefChange.previousRevision=2;briefChange.newRevision=3;
        briefChange.fields.add(new ProjectFieldChange(ProjectField.TITLE,"旧书名","重启后继续的故事"));
        n.projectBriefChanges.add(briefChange);
        repo.insert(n);
        jdbc.execute("SHUTDOWN");
        var second=new DriverManagerDataSource(url,"sa","");
        var reopened=new NovelRepository(new JdbcTemplate(second),new ObjectMapper(),new TransactionTemplate(new DataSourceTransactionManager(second)));
        Novel restored=reopened.get(n.id);
        assertThat(restored.title).isEqualTo(n.title);
        assertThat(restored.artifacts.getFirst().latest().content).isEqualTo("已生成但尚未完成检查的正文");
        assertThat(restored.artifacts.getFirst().latest().draftIssues).containsExactly("摘要待补全");
        assertThat(restored.sourceSnapshots).singleElement().satisfies(saved->{
            assertThat(saved.id).isEqualTo(snapshot.id);
            assertThat(saved.sourceVersionIds).containsExactly("source-v1");
        });
        assertThat(restored.agentRuns).singleElement().satisfies(saved->{
            assertThat(saved.sourceSnapshotId).isEqualTo(snapshot.id);
            assertThat(saved.status).isEqualTo(AgentRunStatus.SUCCEEDED);
            assertThat(saved.resultVersionId).isEqualTo(version.id);
        });
        assertThat(restored.conversationSessions).singleElement().satisfies(saved->{
            assertThat(saved.threadId).isEqualTo("outline-thread-1");
            assertThat(saved.threadTitle).isEqualTo("雨夜主线讨论");
            assertThat(saved.messages).singleElement().extracting(item->item.content).isEqualTo("重启后仍应保留的讨论");
            assertThat(saved.decisions).singleElement().satisfies(item->{
                assertThat(item.status).isEqualTo(DecisionStatus.ACCEPTED);
                assertThat(item.text).isEqualTo("保留雨夜线索");
            });
            assertThat(saved.projectUpdates).singleElement().satisfies(item->{
                assertThat(item.field).isEqualTo(ProjectField.TITLE);
                assertThat(item.status).isEqualTo(ProjectUpdateStatus.APPLIED);
                assertThat(item.proposedValue).isEqualTo("重启后继续的故事");
            });
        });
        assertThat(restored.projectBriefChanges).singleElement().satisfies(saved->{
            assertThat(saved.conversationSessionId).isEqualTo(conversation.id);
            assertThat(saved.previousRevision).isEqualTo(2);
            assertThat(saved.newRevision).isEqualTo(3);
            assertThat(saved.fields).singleElement().satisfies(field->assertThat(field.field()).isEqualTo(ProjectField.TITLE));
        });
        new JdbcTemplate(second).execute("SHUTDOWN");
    }
}
