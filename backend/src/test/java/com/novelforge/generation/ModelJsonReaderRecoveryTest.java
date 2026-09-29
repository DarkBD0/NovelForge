package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.shared.Problem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelJsonReaderRecoveryTest {
    private final ModelJsonReader reader=new ModelJsonReader(new ObjectMapper());

    @Test void normalizesExactChapterOrdinalsInsidePlanRewrite() {
        String json="""
                {"operations":[{"op":"SET_PLAN","plan":{
                  "startChapter":"第十三章","endChapter":"第十四章","prepareNextAfterChapter":"第十三章",
                  "finalBatch":false,"triggerReason":"承接上一批","handoff":"继续追查","assumptions":"",
                  "chapters":[
                    {"number":"第十三章","title":"雨夜来客","purpose":"追查来客"},
                    {"number":"第十四章","title":"旧伞","purpose":"找到旧伞"}
                  ]
                }}]}
                """;

        RewritePatch patch=reader.read(json,RewritePatch.class);

        assertThat(patch.operations()).singleElement().satisfies(operation->{
            assertThat(operation.plan().startChapter).isEqualTo(13);
            assertThat(operation.plan().endChapter).isEqualTo(14);
            assertThat(operation.plan().chapters).extracting(chapter->chapter.number()).containsExactly(13,14);
        });
    }

    @Test void refusesNonUniqueOrSemanticRepairs() {
        String valid="""
                {"operations":[{"op":"SET_FIELD","field":"summary","value":"新摘要"}]}
                """.strip();
        assertThatThrownBy(()->reader.read(valid+"}}",RewritePatch.class))
                .isInstanceOf(Problem.class).hasMessageContaining("无法唯一确定");
        assertThatThrownBy(()->reader.read(valid+"\n"+valid,RewritePatch.class))
                .isInstanceOf(Problem.class).hasMessageContaining("多个 JSON 片段");
    }
}
