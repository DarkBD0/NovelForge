package com.novelforge;

import com.novelforge.canon.CanonService;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.projection.Neo4jProjectionSnapshotReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:formal-memory;DB_CLOSE_DELAY=-1",
        "novelforge.storage.mode=normalized"
})
class FormalMemoryStoreTest {
    @Autowired NovelRepository repository;
    @Autowired CanonService canon;
    @Autowired JdbcTemplate jdbc;
    @Autowired Neo4jProjectionSnapshotReader projectionSnapshots;

    @Test void storesOnlyConfirmedCharacterAndChapterFactsWithHistoryAndIsolation() {
        Novel novel=novel("正式记忆A");
        Novel.Artifact characters=artifact(Novel.Kind.CHARACTERS,0,
                new Novel.Fact("hero","CHARACTER","林舟是修船师","ACTIVE"),
                new Novel.Fact("key","FORESHADOW","铜钥匙用途不明","OPEN"));
        Novel.Artifact chapter=artifact(Novel.Kind.CHAPTER,1,
                new Novel.Fact("arrival","EVENT","林舟回到海港","DONE"),
                new Novel.Fact("key","FORESHADOW","铜钥匙打开维护间","RESOLVED"));
        characters.latest().stateEntities.add(new Novel.StateEntity("character_lin_zhou","CHARACTER","林舟",java.util.List.of(),"修船师"));
        chapter.latest().stateEntities.add(new Novel.StateEntity("character_lin_zhou","CHARACTER","林舟",java.util.List.of("阿舟"),"回到海港的修船师"));
        chapter.latest().stateEntities.add(new Novel.StateEntity("location_harbor","LOCATION","海港",java.util.List.of(),"林舟返回的地点"));
        chapter.latest().stateRelations.add(new Novel.StateRelation("relation_lin_zhou_located_at_harbor","character_lin_zhou",
                "LOCATED_AT","location_harbor","林舟回到海港","ACTIVE"));
        approve(novel,characters); approve(novel,chapter);
        repository.insert(novel);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM canon_fact WHERE novel_id=?",Integer.class,novel.id)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM state_delta WHERE novel_id=?",Integer.class,novel.id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_event WHERE novel_id=?",Integer.class,novel.id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM foreshadow WHERE novel_id=?",String.class,novel.id)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT resolved_chapter FROM foreshadow WHERE novel_id=?",Integer.class,novel.id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM canon_entity WHERE novel_id=?",Integer.class,novel.id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT canonical_name FROM canon_entity WHERE novel_id=? AND entity_key='character_lin_zhou'",String.class,novel.id))
                .isEqualTo("林舟");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM entity_relation WHERE novel_id=?",Integer.class,novel.id)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT relation_key,relation_type,status,valid_to_chapter FROM entity_relation WHERE novel_id=?",novel.id))
                .containsEntry("relation_key","relation_lin_zhou_located_at_harbor")
                .containsEntry("relation_type","LOCATED_AT").containsEntry("status","ACTIVE")
                .containsEntry("valid_to_chapter",null);
        assertThat(jdbc.queryForObject("SELECT schema_version FROM state_delta WHERE artifact_version_id=?",Integer.class,chapter.approvedVersionId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT payload_json FROM state_delta WHERE artifact_version_id=?",String.class,chapter.approvedVersionId))
                .contains("\"entities\"","\"relations\"","relation_lin_zhou_located_at_harbor");
        assertThat(canon.structured(repository.get(novel.id)).entities()).extracting(item->item.key())
                .containsExactly("character_lin_zhou","location_harbor");
        assertThat(canon.structured(repository.get(novel.id)).relations()).singleElement().satisfies(item->{
            assertThat(item.fromEntityKey()).isEqualTo("character_lin_zhou");
            assertThat(item.toEntityKey()).isEqualTo("location_harbor");
            assertThat(item.status()).isEqualTo("ACTIVE");
        });
        var projection=projectionSnapshots.read(novel.id);
        assertThat(projection.entities()).extracting(item->item.get("key"))
                .containsExactly("character_lin_zhou","location_harbor");
        assertThat(projection.relations()).singleElement().satisfies(item->{
            assertThat(item.get("key")).isEqualTo("relation_lin_zhou_located_at_harbor");
            assertThat(item.get("type")).isEqualTo("LOCATED_AT");
            assertThat(item.get("status")).isEqualTo("ACTIVE");
        });

        assertThat(canon.at(repository.get(novel.id),1)).extracting(entry->entry.fact().key())
                .containsExactlyInAnyOrder("hero","key");
        assertThat(canon.at(repository.get(novel.id),Integer.MAX_VALUE)).extracting(entry->entry.fact().key())
                .containsExactlyInAnyOrder("hero","arrival","key");
        assertThat(canon.at(repository.get(novel.id),Integer.MAX_VALUE).stream()
                .filter(entry->entry.fact().key().equals("key")).findFirst().orElseThrow().fact().state()).isEqualTo("RESOLVED");

        Novel other=novel("正式记忆B");
        Novel.Artifact otherCharacters=artifact(Novel.Kind.CHARACTERS,0,new Novel.Fact("other","CHARACTER","另一部小说的人物","ACTIVE"));
        approve(other,otherCharacters); repository.insert(other);
        assertThat(canon.at(repository.get(novel.id),Integer.MAX_VALUE)).noneMatch(entry->entry.fact().key().equals("other"));

        repository.update(novel.id,current->{
            Novel.Version candidate=new Novel.Version(); candidate.title="未确认改稿"; candidate.content="不应进入正式记忆";
            candidate.facts.add(new Novel.Fact("unconfirmed","EVENT","未确认事件","DONE"));
            candidate.stateEntities.add(new Novel.StateEntity("character_intruder","CHARACTER","未确认人物",java.util.List.of(),"不能进入正式记忆"));
            candidate.stateRelations.add(new Novel.StateRelation("relation_intruder_knows_lin_zhou","character_intruder","KNOWS",
                    "character_lin_zhou","未确认关系","ACTIVE"));
            current.artifacts.get(1).versions.add(candidate); current.revision++;
            return null;
        });
        assertThat(canon.at(repository.get(novel.id),Integer.MAX_VALUE)).extracting(entry->entry.fact().key())
                .containsExactlyInAnyOrder("hero","arrival","key");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_event WHERE novel_id=?",Integer.class,novel.id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM canon_fact WHERE fact_key='unconfirmed'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM canon_entity WHERE entity_key='character_intruder'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM entity_relation WHERE relation_key='relation_intruder_knows_lin_zhou'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM canon_entity WHERE novel_id=?",Integer.class,novel.id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM entity_relation WHERE novel_id=?",Integer.class,novel.id)).isEqualTo(1);
        assertThat(projectionSnapshots.read(novel.id).entities()).hasSize(2);
        assertThat(projectionSnapshots.read(novel.id).relations()).hasSize(1);

        repository.update(novel.id,current->null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM canon_entity WHERE novel_id=?",Integer.class,novel.id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM entity_relation WHERE novel_id=?",Integer.class,novel.id)).isEqualTo(1);
    }

    @Test void appliesRelationEndStateWithoutChangingItsStableIdentity() {
        Novel novel=novel("关系结束");
        Novel.Artifact start=artifact(Novel.Kind.CHAPTER,1);
        start.latest().stateEntities.add(new Novel.StateEntity("character_a","CHARACTER","甲",java.util.List.of(),"人物甲"));
        start.latest().stateEntities.add(new Novel.StateEntity("character_b","CHARACTER","乙",java.util.List.of(),"人物乙"));
        start.latest().stateRelations.add(new Novel.StateRelation("relation_a_allies_b","character_a","ALLY","character_b","甲与乙结盟","ACTIVE"));
        Novel.Artifact end=artifact(Novel.Kind.CHAPTER,2);
        end.latest().stateEntities.add(new Novel.StateEntity("character_a","CHARACTER","甲",java.util.List.of(),"人物甲"));
        end.latest().stateEntities.add(new Novel.StateEntity("character_b","CHARACTER","乙",java.util.List.of(),"人物乙"));
        end.latest().stateRelations.add(new Novel.StateRelation("relation_a_allies_b","character_a","ALLY","character_b","甲与乙解除同盟","ENDED"));
        approve(novel,start); approve(novel,end); repository.insert(novel);

        var relation=jdbc.queryForMap("SELECT relation_key,status,valid_from_chapter,valid_to_chapter,source_version_id FROM entity_relation WHERE novel_id=?",novel.id);
        assertThat(relation).containsEntry("relation_key","relation_a_allies_b")
                .containsEntry("status","ENDED")
                .containsEntry("valid_from_chapter",1)
                .containsEntry("valid_to_chapter",2)
                .containsEntry("source_version_id",end.approvedVersionId);
        assertThat(projectionSnapshots.read(novel.id).relations()).singleElement().satisfies(item->{
            assertThat(item.get("status")).isEqualTo("ENDED");
            assertThat(item.get("validFromChapter")).isEqualTo(1);
            assertThat(item.get("validToChapter")).isEqualTo(2);
        });

        assertThatThrownBy(()->repository.update(novel.id,current->{
            Novel.Artifact conflict=artifact(Novel.Kind.CHAPTER,3);
            conflict.latest().stateEntities.add(new Novel.StateEntity("character_a","CHARACTER","甲",java.util.List.of(),"人物甲"));
            conflict.latest().stateEntities.add(new Novel.StateEntity("character_c","CHARACTER","丙",java.util.List.of(),"人物丙"));
            conflict.latest().stateRelations.add(new Novel.StateRelation("relation_a_allies_b","character_a","ALLY","character_c","错误复用关系编号","ACTIVE"));
            approve(current,conflict); current.revision++; return null;
        })).hasMessageContaining("正式关系稳定编号语义冲突");
        assertThat(repository.get(novel.id).artifacts).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT status FROM entity_relation WHERE novel_id=?",String.class,novel.id)).isEqualTo("ENDED");
    }

    private Novel novel(String title) {
        Novel novel=new Novel(); novel.title=title; novel.synopsis="验证正式记忆"; novel.targetWords=10000; novel.approvedMaxWords=11000;
        return novel;
    }
    private Novel.Artifact artifact(Novel.Kind kind,int chapter,Novel.Fact... facts) {
        Novel.Artifact artifact=new Novel.Artifact(); artifact.kind=kind; artifact.chapterNumber=chapter;
        Novel.Version version=new Novel.Version(); version.title="已确认版本"; version.content="已确认内容"; version.summary="摘要";
        version.facts.addAll(java.util.List.of(facts)); artifact.versions.add(version); artifact.approvedVersionId=version.id;
        return artifact;
    }
    private void approve(Novel novel,Novel.Artifact artifact) {
        novel.artifacts.add(artifact);
        novel.approvals.add(new Novel.Approval(artifact.id,artifact.approvedVersionId,novel.revision,"",Novel.now()));
    }
}
