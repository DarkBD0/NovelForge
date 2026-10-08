package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StructuredMemoryContextShadowServiceTest {
    @Test void comparesInputsWithoutCallingAModelOrChangingTheNovel() {
        ContextAssembler assembler=mock(ContextAssembler.class);
        StructuredMemoryContextService memories=mock(StructuredMemoryContextService.class);
        ObjectMapper mapper=new ObjectMapper();
        var compiler=new RoleContextCompiler(mapper,new AgentContextPolicy());
        var service=new StructuredMemoryContextShadowService(assembler,memories,compiler,mapper,
                new SourceSnapshotFactory(),true);
        Novel novel=new Novel(); novel.revision=7; novel.title="影子上下文";
        var context=new ContextAssembler.Context("{\"title\":\"影子上下文\",\"acceptedReferences\":[]}",List.of(),2,1);
        var memory=new StructuredMemoryContextService.Result("CONFIRMED_MYSQL_STRUCTURED_MEMORY",2,
                List.of(new StructuredMemoryContextService.EntityRef("character_lin_che","CHARACTER","林澈",List.of(),
                        "维修员","chapter-1",1,"CURRENT")),List.of(),List.of("chapter-1"),"正式记忆");
        when(assembler.assemble(same(novel),eq(Novel.Action.CHAPTER),isNull())).thenReturn(context);
        when(memories.beforeChapter(eq(novel),eq(2),anyString())).thenReturn(memory);

        var report=service.evaluate(novel);

        assertThat(report.status()).isEqualTo("READY");
        assertThat(report.shadowOnly()).isTrue();
        assertThat(report.scope()).isEqualTo("INPUT_CONTEXT_ONLY");
        assertThat(report.entities()).isEqualTo(1);
        assertThat(report.comparisons()).hasSize(8).allSatisfy(comparison->{
            assertThat(comparison.shadowChars()).isGreaterThan(comparison.officialChars());
            assertThat(comparison.officialHash()).isNotEqualTo(comparison.shadowHash());
            assertThat(comparison.addedSourceVersionIds()).containsExactly("chapter-1");
        });
        assertThat(novel.revision).isEqualTo(7);
    }
}
