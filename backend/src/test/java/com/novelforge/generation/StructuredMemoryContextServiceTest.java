package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.canon.CanonService;
import com.novelforge.canon.FormalMemoryStore;
import com.novelforge.novel.Novel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StructuredMemoryContextServiceTest {
    @Test void exposesOnlyConfirmedMemoryBeforeTheCurrentChapterAndKeepsEndedStateExplicit() {
        CanonService canon=mock(CanonService.class);
        Novel novel=new Novel();
        var lin=new FormalMemoryStore.EntityMemory("e1","character_lin_che","CHARACTER","林澈",List.of(),
                "维修员","chapter-1",1,"CURRENT");
        var zhou=new FormalMemoryStore.EntityMemory("e2","character_zhou_lan","CHARACTER","周岚",List.of(),
                "站长","chapter-1",1,"CURRENT");
        var future=new FormalMemoryStore.EntityMemory("e3","character_future","CHARACTER","未来人物",List.of(),
                "尚未登场","chapter-3",3,"CURRENT");
        var ended=new FormalMemoryStore.RelationMemory("r1","relation_colleague","e1","character_lin_che",
                "COLLEAGUE","e2","character_zhou_lan","同事关系已经结束","chapter-1",1,1,"ENDED");
        when(canon.structured(novel)).thenReturn(new FormalMemoryStore.StructuredMemory(
                List.of(lin,zhou,future),List.of(ended)));

        var result=new StructuredMemoryContextService(canon).beforeChapter(novel,2);

        assertThat(result.entities()).extracting("key")
                .containsExactly("character_lin_che","character_zhou_lan");
        assertThat(result.relations()).singleElement().satisfies(relation->{
            assertThat(relation.type()).isEqualTo("COLLEAGUE");
            assertThat(relation.status()).isEqualTo("ENDED");
        });
        assertThat(result.sourceVersionIds()).containsExactly("chapter-1");
        assertThat(result.authorityState()).isEqualTo("CONFIRMED_MYSQL_STRUCTURED_MEMORY");
        assertThat(result.usedChars()).isLessThanOrEqualTo(result.maxChars());
        assertThat(result.selectionPolicyVersion()).isEqualTo("structured-memory-selection-v2");
    }

    @Test void distantRelevantRelationWinsOverManyRecentUnrelatedEntitiesWithinTheCharacterBudget() {
        CanonService canon=mock(CanonService.class);
        Novel novel=new Novel();
        var entities=new java.util.ArrayList<FormalMemoryStore.EntityMemory>();
        for(int chapter=2;chapter<=121;chapter++) entities.add(new FormalMemoryStore.EntityMemory("f"+chapter,
                "character_filler_"+chapter,"CHARACTER","路人"+chapter,List.of(),"近期无关人物"+chapter,
                "chapter-"+chapter,chapter,"CURRENT"));
        var linxi=new FormalMemoryStore.EntityMemory("target-1","character_lin_xi","CHARACTER","林溪",List.of("妹妹"),
                "三年前成为因果锚点","chapter-1",1,"CURRENT");
        var oldClock=new FormalMemoryStore.EntityMemory("target-2","item_old_clock","ITEM","旧钟",List.of(),
                "承载内层回声映射","chapter-1",1,"CURRENT");
        entities.add(linxi); entities.add(oldClock);
        var relation=new FormalMemoryStore.RelationMemory("relation-1","relation_linxi_anchored_clock",
                "target-1","character_lin_xi","RELATED_TO","target-2","item_old_clock",
                "林溪的回声与旧钟内层映射相关","chapter-1",1,null,"ACTIVE");
        when(canon.structured(novel)).thenReturn(new FormalMemoryStore.StructuredMemory(entities,List.of(relation)));
        var service=new StructuredMemoryContextService(canon,new ObjectMapper(),1600,5,5);

        var result=service.beforeChapter(novel,122,"林溪是否仍与旧钟内层的回声有关？");

        assertThat(result.entities()).extracting("key").contains("character_lin_xi","item_old_clock");
        assertThat(result.relations()).extracting("key").containsExactly("relation_linxi_anchored_clock");
        assertThat(result.entities()).hasSizeLessThanOrEqualTo(5);
        assertThat(result.usedChars()).isLessThanOrEqualTo(1600);
    }

    @Test void aliasDisambiguatesEntitiesThatShareTheSameDisplayName() {
        CanonService canon=mock(CanonService.class);
        Novel novel=new Novel();
        var doctor=new FormalMemoryStore.EntityMemory("e1","character_zhou_huilan","CHARACTER","老周",
                List.of("周慧兰"),"县医院医生，穿蓝布衫","chapter-2",2,"CURRENT");
        var mechanic=new FormalMemoryStore.EntityMemory("e2","character_zhou_shifu","CHARACTER","老周",
                List.of("周师傅"),"修理铺老板","chapter-20",20,"CURRENT");
        when(canon.structured(novel)).thenReturn(new FormalMemoryStore.StructuredMemory(
                List.of(doctor,mechanic),List.of()));
        var service=new StructuredMemoryContextService(canon,new ObjectMapper(),900,1,0);

        var result=service.beforeChapter(novel,30,"周慧兰穿着蓝布衫回到县医院。她隐瞒了什么？");

        assertThat(result.entities()).extracting("key").containsExactly("character_zhou_huilan");
        assertThat(result.usedChars()).isLessThanOrEqualTo(900);
    }
}
