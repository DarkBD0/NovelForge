package com.novelforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.projection.ProjectionOutbox;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:projection-outbox;DB_CLOSE_DELAY=-1",
        "novelforge.storage.mode=normalized",
        "novelforge.projections.neo4j.enabled=false"
})
class ProjectionOutboxTest {
    @Autowired NovelRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;

    @Test void deduplicatesRetriesCheckpointsAndRecoversInterruptedProjection() {
        Novel novel=new Novel();
        novel.title="投影任务验证"; novel.synopsis="验证 Outbox";
        novel.targetWords=10000; novel.approvedMaxWords=11000;
        repository.insert(novel);

        ProjectionOutbox outbox=new ProjectionOutbox(jdbc,mapper,new TransactionTemplate(transactionManager),true,false);
        outbox.enqueueNeo4jSnapshot(novel.id,"hash-1");
        outbox.enqueueNeo4jSnapshot(novel.id,"hash-1");
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE novel_id=?",novel.id)).isEqualTo(1);

        outbox.enqueueNeo4jSnapshot(novel.id,"hash-2");
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE novel_id=? AND status='SUPERSEDED'",novel.id)).isEqualTo(1);
        assertThat(outbox.status().pending()).isEqualTo(1);

        ProjectionOutbox.Event first=outbox.claimNextNeo4j();
        assertThat(first).isNotNull();
        assertThat(first.memoryHash()).isEqualTo("hash-2");
        outbox.failed(first,new IllegalStateException("临时不可用"));
        assertThat(outbox.status().pending()).isEqualTo(1);

        jdbc.update("UPDATE outbox_event SET next_attempt_at=NULL WHERE id=?",first.id());
        ProjectionOutbox.Event retry=outbox.claimNextNeo4j();
        assertThat(retry.attempts()).isEqualTo(2);
        outbox.succeeded(retry);
        assertThat(outbox.status()).isEqualTo(new ProjectionOutbox.Status(true,0,0,0,1,1));
        assertThat(jdbc.queryForObject("SELECT metadata_json FROM projection_checkpoint WHERE target=?",String.class,
                "NEO4J:"+novel.id)).contains("hash-2","\"schemaVersion\":3");

        jdbc.update("UPDATE projection_checkpoint SET metadata_json=? WHERE target=?",
                "{\"memoryHash\":\"hash-2\",\"schemaVersion\":2}","NEO4J:"+novel.id);
        outbox.enqueueNeo4jSnapshot(novel.id,"hash-2");
        assertThat(outbox.status().pending()).isEqualTo(1);
        ProjectionOutbox.Event schemaUpgrade=outbox.claimNextNeo4j();
        assertThat(schemaUpgrade.memoryHash()).isEqualTo("hash-2");
        outbox.succeeded(schemaUpgrade);

        outbox.enqueueNeo4jSnapshot(novel.id,"hash-3");
        ProjectionOutbox.Event interrupted=outbox.claimNextNeo4j();
        assertThat(interrupted).isNotNull();
        assertThat(outbox.status().processing()).isEqualTo(1);
        outbox.enqueueMissingNeo4jSnapshots();
        assertThat(outbox.status().processing()).isZero();
        assertThat(outbox.status().pending()).isEqualTo(1);

        ProjectionOutbox elasticsearch=new ProjectionOutbox(jdbc,mapper,new TransactionTemplate(transactionManager),false,true);
        elasticsearch.enqueueElasticsearchSnapshot(novel.id,"text-hash-1");
        ProjectionOutbox.Event textEvent=elasticsearch.claimNextElasticsearch();
        assertThat(textEvent.target()).isEqualTo(ProjectionOutbox.TARGET_ELASTICSEARCH);
        assertThat(textEvent.eventType()).isEqualTo(ProjectionOutbox.EVENT_CONFIRMED_TEXT_SNAPSHOT);
        elasticsearch.succeeded(textEvent);
        assertThat(elasticsearch.elasticsearchStatus()).isEqualTo(new ProjectionOutbox.Status(true,0,0,0,1,1));
    }

    private int count(String sql,String novelId) {
        Integer value=jdbc.queryForObject(sql,Integer.class,novelId);
        return value==null?0:value;
    }
}
