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
        new JdbcTemplate(second).execute("SHUTDOWN");
    }
}
