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

class HistoricalStructuredMemoryContinuityAbServiceTest {
    @Test void reconstructsOnceAndComparesTheSameCandidateWithoutChangingTheNovel() {
        ContextAssembler assembler=mock(ContextAssembler.class);
        HistoricalStructuredMemoryShadowService historical=mock(HistoricalStructuredMemoryShadowService.class);
        ModelGateway model=mock(ModelGateway.class);
        ReviewAggregator reviews=mock(ReviewAggregator.class);
        ContinuityReviewPolicy continuity=mock(ContinuityReviewPolicy.class);
        ObjectMapper mapper=new ObjectMapper();
        var compiler=new RoleContextCompiler(mapper,new AgentContextPolicy());
        var memorySelector=new StructuredMemoryContextService(mock(com.novelforge.canon.CanonService.class),
                mapper,6000,40,80);
        var service=new HistoricalStructuredMemoryContinuityAbService(assembler,historical,compiler,model,reviews,
                continuity,mapper,new HistoricalEvidenceReviewPolicy(),memorySelector,
                new HistoricalClaimEvidencePairingService(),new SourceSnapshotFactory(),true);
        Novel novel=new Novel(); novel.id="novel-1"; novel.revision=19; novel.title="长篇验收";
        var base=new ContextAssembler.Context("{\"title\":\"长篇验收\",\"acceptedReferences\":[]}",
                List.of("current-plan"),30,2);
        var aggregate=new HistoricalStructuredMemoryShadowService.Aggregate(
                "MODEL_DERIVED_HISTORICAL_SHADOW",
                List.of(new HistoricalStructuredMemoryShadowService.ShadowFact("fact_old_clock_stopped","WORLD",
                        "旧钟楼机械钟早已停摆","CURRENT",1,1,List.of("chapter-1"),List.of(
                                new HistoricalStructuredMemoryShadowService.ShadowEvidence(
                                        "旧钟楼机械钟早已停摆","chapter-1",1)))),
                List.of(new HistoricalStructuredMemoryShadowService.ShadowEntity("location_old_clock_tower",
                        "LOCATION","旧钟楼",List.of(),"机械钟已经停摆",1,1,List.of("chapter-1"),List.of(
                                new HistoricalStructuredMemoryShadowService.ShadowEvidence(
                                        "旧钟楼机械钟早已停摆","chapter-1",1)))),
                List.of(),"只读影子");
        var reconstructed=new HistoricalStructuredMemoryShadowService.Report("SUCCEEDED",true,
                "SELECTED_CONFIRMED_CHAPTERS_READ_ONLY",HistoricalStructuredMemoryShadowService.POLICY_VERSION,
                "memory-key",19,List.of(1),1,List.of(),aggregate,"不写入");
        when(historical.enabled()).thenReturn(true);
        when(historical.evaluate(same(novel),startsWith("continuity-ab-memory-"),eq(List.of(1))))
                .thenReturn(reconstructed);
        when(assembler.assemble(same(novel),eq(Novel.Action.CHAPTER),isNull())).thenReturn(base);
        when(model.ready()).thenReturn(true);
        AtomicInteger calls=new AtomicInteger();
        Review empty=new Review(true,List.of(),false,false,false,List.of());
        ReviewIssue issue=new ReviewIssue("CONTINUITY:WORLD_RULE:HIGH","候选正文","旧钟状态冲突",
                "冲突对象：旧钟｜冲突属性：世界规则｜原状态时间：第一章｜候选状态时间：当前章节｜"
                        +"同一时点：“是”｜推进授权：“无”｜候选原文：“机械钟一直正常运转”｜"
                        +"已确认依据：“旧钟楼机械钟早已停摆”","核对旧钟状态","必须修正");
        ReviewIssue ungrounded=new ReviewIssue("CONTINUITY:RELATION:HIGH","候选正文","关系冲突",
                "冲突对象：甲乙｜冲突属性：关系｜原状态时间：第一章｜候选状态时间：当前章节｜"
                        +"同一时点：“是”｜推进授权：“无”｜候选原文：“没有关系”｜"
                        +"已确认依据：“模型自行概括但正文没有这句话”","核对关系","必须修正");
        Review finding=new Review(true,List.of(issue.text(),ungrounded.text()),false,false,false,
                List.of(issue,ungrounded));
        when(model.continuityReview(any(),any())).thenAnswer(invocation->{
            ModelGateway.Request request=invocation.getArgument(0);
            int call=calls.incrementAndGet();
            if(call==1&&!request.context().json().contains("MODEL_DERIVED_HISTORICAL_SHADOW"))
                throw new Problem(502,"模型 JSON 不完整");
            return request.context().json().contains("MODEL_DERIVED_HISTORICAL_SHADOW")?finding:empty;
        });
        when(reviews.aggregateShadow(any(),any())).thenAnswer(invocation->invocation.getArgument(0));
        when(continuity.normalize(any())).thenAnswer(invocation->invocation.getArgument(0));
        var candidate=new HistoricalStructuredMemoryContinuityAbService.Candidate("第三十章",
                "调查组确认旧钟楼的机械钟一直正常运转。","调查确认旧钟正常运转");

        var report=service.evaluate(novel,"historical-ab-1",List.of(1),candidate);
        var cached=service.evaluate(novel,"historical-ab-1",List.of(1),candidate);

        assertThat(report.status()).isEqualTo("SUCCEEDED");
        assertThat(report.reconstruction()).isSameAs(reconstructed);
        assertThat(report.official().acceptedFindings()).isZero();
        assertThat(report.historicalMemory().acceptedFindings()).isOne();
        assertThat(report.historicalMemory().rawFindings()).isEqualTo(2);
        assertThat(report.historicalMemory().review().issueDetails()).containsExactly(issue);
        assertThat(report.historicalMemory().contextPolicyVersion())
                .isEqualTo(AgentContextPolicy.HISTORICAL_CONTINUITY_SHADOW_VERSION);
        assertThat(report.official().contextHash()).isNotEqualTo(report.historicalMemory().contextHash());
        assertThat(report.historicalMemoryOnlyFindings()).containsExactly("CONTINUITY:WORLD_RULE:HIGH");
        assertThat(cached).isSameAs(report);
        assertThat(calls).hasValue(3);
        verify(historical,times(1)).evaluate(same(novel),startsWith("continuity-ab-memory-"),eq(List.of(1)));
        assertThat(novel.revision).isEqualTo(19);
        assertThatThrownBy(()->service.evaluate(novel,"historical-ab-1",List.of(1),
                new HistoricalStructuredMemoryContinuityAbService.Candidate("第三十章","另一份正文","")))
                .isInstanceOf(Problem.class).hasMessageContaining("幂等键");
    }
}
