package com.novelforge;

import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.projection.ElasticsearchProjectionSnapshotReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:confirmed-text;DB_CLOSE_DELAY=-1",
        "novelforge.storage.mode=normalized",
        "novelforge.projections.elasticsearch.enabled=false"
})
class ConfirmedTextProjectionStoreTest {
    @Autowired NovelRepository repository;
    @Autowired ElasticsearchProjectionSnapshotReader snapshots;
    @Autowired JdbcTemplate jdbc;

    @Test void exposesOnlyCleanConfirmedCharacterAndChapterVersions() {
        Novel novel=new Novel(); novel.title="确认文本投影"; novel.synopsis="验证索引边界";
        novel.targetWords=10000; novel.approvedMaxWords=11000;
        Novel.Artifact characters=artifact(Novel.Kind.CHARACTERS,0,"人物设定正文");
        Novel.Artifact chapter=artifact(Novel.Kind.CHAPTER,1,"已确认章节正文");
        novel.artifacts.add(characters); novel.artifacts.add(chapter);
        repository.insert(novel);

        assertThat(snapshots.read(novel.id)).hasSize(2)
                .extracting(item->item.get("text")).containsExactly("人物设定正文","已确认章节正文");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM projection_checkpoint WHERE target=?",Integer.class,
                "CONFIRMED_TEXT:"+novel.id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE target='ELASTICSEARCH'",Integer.class)).isZero();

        repository.update(novel.id,current->{
            Novel.Version candidate=new Novel.Version(); candidate.title="未确认候选"; candidate.content="不能进入索引"; candidate.summary="候选";
            current.artifacts.get(1).versions.add(candidate); current.revision++;
            return null;
        });
        assertThat(snapshots.read(novel.id)).hasSize(2)
                .extracting(item->item.get("text")).containsExactly("人物设定正文","已确认章节正文");
    }

    private Novel.Artifact artifact(Novel.Kind kind,int chapter,String content) {
        Novel.Artifact artifact=new Novel.Artifact(); artifact.kind=kind; artifact.chapterNumber=chapter;
        Novel.Version version=new Novel.Version(); version.title=kind.name(); version.content=content; version.summary="摘要";
        artifact.versions.add(version); artifact.approvedVersionId=version.id;
        return artifact;
    }
}
