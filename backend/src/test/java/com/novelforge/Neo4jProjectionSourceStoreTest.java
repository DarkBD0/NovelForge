package com.novelforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.projection.Neo4jProjectionSnapshotReader;
import com.novelforge.projection.Neo4jProjectionSourceStore;
import com.novelforge.projection.ProjectionOutbox;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:neo4j-source;DB_CLOSE_DELAY=-1",
        "novelforge.storage.mode=normalized",
        "novelforge.projections.neo4j.enabled=false"
})
class Neo4jProjectionSourceStoreTest {
    @Autowired NovelRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired Neo4jProjectionSnapshotReader snapshots;
    @Autowired PlatformTransactionManager transactionManager;

    @Test void dependencyOnlyChangesProduceANewRecoverableProjectionSnapshot() {
        Novel novel=new Novel(); novel.title="依赖投影"; novel.synopsis="验证版本依赖";
        novel.targetWords=1000; novel.approvedMaxWords=1100;
        Novel.Artifact outline=artifact(Novel.Kind.OUTLINE,"outline-version");
        Novel.Artifact chapter=artifact(Novel.Kind.CHAPTER,"chapter-version","outline-version"); chapter.chapterNumber=1;
        novel.artifacts.addAll(List.of(outline,chapter)); repository.insert(novel);

        ProjectionOutbox outbox=new ProjectionOutbox(jdbc,mapper,new TransactionTemplate(transactionManager),true,false);
        Neo4jProjectionSourceStore store=new Neo4jProjectionSourceStore(jdbc,mapper,snapshots,outbox,"normalized",true);
        var first=store.synchronize(novel.id);
        assertThat(first.changed()).isTrue(); assertThat(first.artifacts()).isEqualTo(2);
        assertThat(first.versions()).isEqualTo(2); assertThat(first.dependencies()).isEqualTo(1);
        assertThat(store.synchronize(novel.id).changed()).isFalse();

        repository.update(novel.id,current->{
            current.artifacts.get(1).latest().sourceVersionIds=List.of(); current.revision++; return null;
        });
        var second=store.synchronize(novel.id);
        assertThat(second.changed()).isTrue(); assertThat(second.dependencies()).isZero();
        assertThat(second.snapshotHash()).isNotEqualTo(first.snapshotHash());
        assertThat(jdbc.queryForObject("SELECT metadata_json FROM projection_checkpoint WHERE target=?",String.class,
                "NEO4J_SOURCE:"+novel.id)).contains("\"schemaVersion\":3","\"dependencies\":0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE novel_id=? AND target='NEO4J' AND status='PENDING'",
                Integer.class,novel.id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE novel_id=? AND status='SUPERSEDED'",
                Integer.class,novel.id)).isEqualTo(1);
    }

    private Novel.Artifact artifact(Novel.Kind kind,String versionId,String...dependencies) {
        Novel.Artifact artifact=new Novel.Artifact(); artifact.kind=kind;
        Novel.Version version=new Novel.Version(); version.id=versionId; version.title="标题"; version.content="内容";
        version.sourceVersionIds=List.of(dependencies); artifact.versions.add(version); artifact.approvedVersionId=version.id;
        return artifact;
    }
}
