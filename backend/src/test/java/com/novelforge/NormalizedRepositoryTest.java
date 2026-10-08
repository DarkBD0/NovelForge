package com.novelforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:normalized-repository;DB_CLOSE_DELAY=-1",
        "novelforge.storage.mode=normalized"
})
class NormalizedRepositoryTest {
    @Autowired NovelRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test void readsAndUpdatesOnlyFromSplitTablesWithoutDeletingFutureMemory() throws Exception {
        Novel novel=new Novel(); novel.title="拆分存储"; novel.synopsis="验证关系表主读"; novel.targetWords=10000; novel.approvedMaxWords=11000;
        Novel.Artifact artifact=new Novel.Artifact(); artifact.kind=Novel.Kind.CHAPTER; artifact.chapterNumber=1;
        Novel.Version version=new Novel.Version(); version.title="第一章"; version.content="雨夜里，他看见一盏灯。"; version.summary="看见灯";
        version.facts.add(new Novel.Fact("灯","物品","雨夜里的灯","出现"));
        version.stateExtractionRequired=true; version.stateExtractionStatus=Novel.StateExtractionStatus.FAILED;
        version.stateExtractionPolicyVersion="state-extraction-v1";
        version.stateExtractionSourceHash="abcdef"; version.stateExtractionAgentRunId="state-run-1";
        version.stateExtractionError="等待只重试状态提取";
        version.stateEvidence.add(new Novel.StateEvidence("灯",java.util.List.of("他看见一盏灯")));
        version.stateEntities.add(new Novel.StateEntity("item_lamp","ITEM","灯",java.util.List.of("灯光"),"雨夜里的灯"));
        version.stateRelations.add(new Novel.StateRelation("relation_person_owns_lamp","character_person","OWNS",
                "item_lamp","这个人持有灯","ACTIVE"));
        artifact.versions.add(version); artifact.approvedVersionId=version.id; novel.artifacts.add(artifact);
        Novel.Task task=new Novel.Task(); task.action=Novel.Action.CHAPTER; task.status=Novel.TaskStatus.SUCCEEDED; novel.tasks.add(task);
        Novel.AgentRun run=new Novel.AgentRun(); run.taskId=task.id; run.role="CHAPTER_WRITER"; run.operation="generate";
        run.contextPolicyVersion="agent-context-v1"; run.contextJson="{\"agentContext\":{\"role\":\"CHAPTER_WRITER\"}}";
        run.contextHash="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        run.contextSourceVersionIds.add(version.id); run.contextChapterNumber=2; run.contextBatchNumber=1;
        run.status=Novel.AgentRunStatus.SUCCEEDED; novel.agentRuns.add(run);
        Novel.ConversationSession session=new Novel.ConversationSession(); session.threadId="thread-1";
        Novel.ConversationMessage message=new Novel.ConversationMessage(); message.role=Novel.ConversationRole.USER; message.content="保留雨夜";
        session.messages.add(message); novel.conversationSessions.add(session);
        novel.approvals.add(new Novel.Approval(artifact.id,version.id,novel.revision,"",Novel.now()));
        novel.budgetChanges.add(new Novel.BudgetChange(null,novel.targetWords,novel.approvedMaxWords,
                novel.approvedMaxWords,"初始字数",Novel.now()));
        repository.insert(novel);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novels",Integer.class)).isZero();
        Novel restored=repository.get(novel.id);
        assertThat((com.fasterxml.jackson.databind.JsonNode)mapper.valueToTree(restored))
                .isEqualTo(mapper.valueToTree(novel));
        assertThat(restored.agentRuns).singleElement().satisfies(saved->{
            assertThat(saved.contextJson).isEqualTo(run.contextJson);
            assertThat(saved.contextHash).isEqualTo(run.contextHash);
            assertThat(saved.contextSourceVersionIds).containsExactly(version.id);
        });
        assertThat(restored.artifacts.getFirst().latest()).satisfies(saved->{
            assertThat(saved.stateExtractionStatus).isEqualTo(Novel.StateExtractionStatus.FAILED);
            assertThat(saved.stateExtractionError).isEqualTo("等待只重试状态提取");
            assertThat(saved.stateEvidence).singleElement().satisfies(evidence->
                    assertThat(evidence.evidenceQuotes()).containsExactly("他看见一盏灯"));
            assertThat(saved.stateEntities).singleElement().satisfies(entity->{
                assertThat(entity.key()).isEqualTo("item_lamp");
                assertThat(entity.aliases()).containsExactly("灯光");
            });
            assertThat(saved.stateRelations).singleElement().satisfies(relation->
                    assertThat(relation.toEntityKey()).isEqualTo("item_lamp"));
        });

        jdbc.update("INSERT INTO canon_entity(id,novel_id,entity_key,entity_type,canonical_name,aliases_json,description,source_version_id,valid_from_chapter,status) VALUES(?,?,?,?,?,?,?,?,?,?)",
                "entity-1",novel.id,"legacy_lamp","OBJECT","灯","[]","雨夜里的灯",version.id,1,"CONFIRMED");
        Long approvalRowId=jdbc.queryForObject("SELECT id FROM approval WHERE novel_id=? AND ordinal_no=0",Long.class,novel.id);
        String taskHash=jdbc.queryForObject("SELECT record_hash FROM generation_task WHERE id=?",String.class,task.id);
        String messageHash=jdbc.queryForObject("SELECT record_hash FROM conversation_message WHERE id=?",String.class,message.id);
        String versionHash=jdbc.queryForObject("SELECT record_hash FROM artifact_version WHERE id=?",String.class,version.id);
        repository.update(novel.id,current->{current.title="拆表后更新";current.revision++;return null;});
        assertThat(repository.get(novel.id).title).isEqualTo("拆表后更新");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM canon_entity WHERE novel_id=?",Integer.class,novel.id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM approval WHERE novel_id=? AND ordinal_no=0",Long.class,novel.id)).isEqualTo(approvalRowId);
        assertThat(jdbc.queryForObject("SELECT record_hash FROM generation_task WHERE id=?",String.class,task.id)).isEqualTo(taskHash);
        assertThat(jdbc.queryForObject("SELECT record_hash FROM conversation_message WHERE id=?",String.class,message.id)).isEqualTo(messageHash);
        assertThat(jdbc.queryForObject("SELECT record_hash FROM artifact_version WHERE id=?",String.class,version.id)).isEqualTo(versionHash);

        repository.update(novel.id,current->{
            current.artifacts.getFirst().versions.getFirst().content="雨夜里，他看见两盏灯。";
            current.revision++; return null;
        });
        assertThat(jdbc.queryForObject("SELECT record_hash FROM artifact_version WHERE id=?",String.class,version.id)).isNotEqualTo(versionHash);
        assertThat(jdbc.queryForObject("SELECT record_hash FROM generation_task WHERE id=?",String.class,task.id)).isEqualTo(taskHash);
        assertThat(jdbc.queryForObject("SELECT record_hash FROM conversation_message WHERE id=?",String.class,message.id)).isEqualTo(messageHash);
        assertThat(jdbc.queryForObject("SELECT id FROM approval WHERE novel_id=? AND ordinal_no=0",Long.class,novel.id)).isEqualTo(approvalRowId);

        repository.update(novel.id,current->{current.conversationSessions.getFirst().messages.clear();current.revision++;return null;});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation_message WHERE novel_id=?",Integer.class,novel.id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artifact_version WHERE novel_id=?",Integer.class,novel.id)).isEqualTo(1);

        assertThatThrownBy(()->repository.update(novel.id,current->{current.title="不应提交";throw new IllegalStateException("rollback");}));
        assertThat(repository.get(novel.id).title).isEqualTo("拆表后更新");

        assertThatThrownBy(()->repository.update(novel.id,current->{
            current.title="增量写入中途失败"; current.revision++;
            Novel.Artifact invalid=new Novel.Artifact(); invalid.id=null; invalid.kind=Novel.Kind.CHAPTER; invalid.chapterNumber=2;
            current.artifacts.add(invalid); return null;
        }));
        assertThat(repository.get(novel.id).title).isEqualTo("拆表后更新");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artifact WHERE novel_id=?",Integer.class,novel.id)).isEqualTo(1);
    }
}
