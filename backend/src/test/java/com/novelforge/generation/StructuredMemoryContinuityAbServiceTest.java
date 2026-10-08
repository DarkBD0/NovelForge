package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import com.novelforge.shared.Problem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StructuredMemoryContinuityAbServiceTest {
    @Test void comparesTwoRealInputsWithoutChangingTheNovelAndCachesByRequestKey() {
        ContextAssembler assembler=mock(ContextAssembler.class);
        StructuredMemoryContextService memories=mock(StructuredMemoryContextService.class);
        ModelGateway model=mock(ModelGateway.class);
        ReviewAggregator reviews=mock(ReviewAggregator.class);
        ContinuityReviewPolicy continuity=mock(ContinuityReviewPolicy.class);
        ObjectMapper mapper=new ObjectMapper();
        var compiler=new RoleContextCompiler(mapper,new AgentContextPolicy());
        var service=new StructuredMemoryContinuityAbService(assembler,memories,compiler,model,reviews,continuity,
                mapper,new SourceSnapshotFactory(),true);
        Novel novel=new Novel(); novel.id="novel-1"; novel.revision=7; novel.title="影子验收";
        var base=new ContextAssembler.Context("{\"title\":\"影子验收\",\"acceptedReferences\":[]}",List.of(),2,1);
        var memory=new StructuredMemoryContextService.Result("CONFIRMED_MYSQL_STRUCTURED_MEMORY",2,
                List.of(new StructuredMemoryContextService.EntityRef("character_lin_che","CHARACTER","林澈",List.of(),
                        "维修员","chapter-1",1,"CURRENT")),List.of(),List.of("chapter-1"),"正式记忆");
        when(assembler.assemble(same(novel),eq(Novel.Action.CHAPTER),isNull())).thenReturn(base);
        when(memories.beforeChapter(eq(novel),eq(2),contains("林澈进入站台"))).thenReturn(memory);
        when(model.ready()).thenReturn(true);
        AtomicInteger calls=new AtomicInteger();
        Review empty=new Review(true,List.of(),false,false,false,List.of());
        ReviewIssue issue=new ReviewIssue("CONTINUITY:IDENTITY:HIGH","候选","身份冲突","依据","核对","作者决定");
        Review finding=new Review(true,List.of(issue.text()),false,false,false,List.of(issue));
        when(model.continuityReview(any(),any())).thenAnswer(invocation->{
            ModelGateway.Request request=invocation.getArgument(0);
            calls.incrementAndGet();
            return request.context().json().contains("formalStructuredMemory")?finding:empty;
        });
        when(reviews.aggregateShadow(any(),any())).thenAnswer(invocation->invocation.getArgument(0));
        when(continuity.normalize(any())).thenAnswer(invocation->invocation.getArgument(0));
        var candidate=new StructuredMemoryContinuityAbService.Candidate("第二章","林澈进入站台。","继续调查");

        var report=service.evaluate(novel,"ab-1",candidate);
        var cached=service.evaluate(novel,"ab-1",candidate);

        assertThat(report.status()).isEqualTo("SUCCEEDED");
        assertThat(report.official().acceptedFindings()).isZero();
        assertThat(report.structuredMemory().acceptedFindings()).isEqualTo(1);
        assertThat(report.official().contextHash()).isNotEqualTo(report.structuredMemory().contextHash());
        assertThat(report.structuredMemoryOnlyFindings()).containsExactly("CONTINUITY:IDENTITY:HIGH");
        assertThat(cached).isSameAs(report);
        assertThat(calls).hasValue(2);
        assertThat(novel.revision).isEqualTo(7);
        assertThatThrownBy(()->service.evaluate(novel,"ab-1",
                new StructuredMemoryContinuityAbService.Candidate("第二章","不同正文","")))
                .isInstanceOf(Problem.class).hasMessageContaining("幂等键");
    }
}
