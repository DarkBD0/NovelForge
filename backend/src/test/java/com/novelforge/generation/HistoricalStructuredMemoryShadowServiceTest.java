package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel;
import com.novelforge.shared.Problem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalStructuredMemoryShadowServiceTest {
    @Test void rebuildsSelectedConfirmedChaptersWithoutMutatingOrPersistingAndCachesTheResult() {
        ModelGateway model=mock(ModelGateway.class);
        ObjectMapper mapper=new ObjectMapper();
        var compiler=new RoleContextCompiler(mapper,new AgentContextPolicy());
        var service=new HistoricalStructuredMemoryShadowService(model,compiler,new StateExtractionPolicy(),mapper,
                new SourceSnapshotFactory(),true,5);
        Novel novel=new Novel(); novel.id="novel-long"; novel.title="零点回声"; novel.synopsis="记忆悬疑";
        novel.requirements="保持因果闭合"; novel.revision=42;
        Novel.Artifact first=chapter(1,"林砚握住旧钟，听见林溪的声音。");
        Novel.Artifact last=chapter(30,"林溪走进钟表铺，旧钟传来林砚的声音。");
        novel.artifacts.add(first); novel.artifacts.add(last);
        when(model.ready()).thenReturn(true);
        AtomicInteger calls=new AtomicInteger();
        when(model.extractState(any(),any())).thenAnswer(invocation->{
            ModelGateway.Request request=invocation.getArgument(0);
            ModelGateway.Generated candidate=invocation.getArgument(1);
            int call=calls.incrementAndGet();
            if(call==2) assertThat(request.context().json())
                    .contains("MODEL_DERIVED_HISTORICAL_SHADOW","character_lin_xi",first.approvedVersionId);
            String evidence=call==1?"林砚握住旧钟":"林溪走进钟表铺";
            return new ModelGateway.StateExtraction(
                    List.of(new ModelGateway.ExtractedState("event_clock_echo","EVENT",
                            call==1?"林砚听见旧钟回声":"旧钟再次传出林砚的声音","ACTIVE",List.of(evidence))),
                    List.of(
                            new ModelGateway.ExtractedEntity("character_lin_xi","CHARACTER","林溪",List.of(),
                                    "与旧钟回声有关",List.of(evidence)),
                            new ModelGateway.ExtractedEntity("item_old_clock","ITEM","旧钟",List.of(),
                                    "承载回声",List.of(evidence))),
                    List.of(new ModelGateway.ExtractedRelation("relation_linxi_clock","character_lin_xi",
                            "RELATED_TO","item_old_clock","林溪与旧钟回声相关","ACTIVE",List.of(evidence))));
        });

        var report=service.evaluate(novel,"fixed-set-1",List.of(30,1,30));
        var cached=service.evaluate(novel,"fixed-set-1",List.of(1,30));

        assertThat(report.status()).isEqualTo("SUCCEEDED");
        assertThat(report.shadowOnly()).isTrue();
        assertThat(report.requestedChapters()).containsExactly(1,30);
        assertThat(report.modelCalls()).isEqualTo(2);
        assertThat(report.chapters()).extracting("attempts").containsExactly(1,1);
        assertThat(report.aggregate().authorityState()).isEqualTo("MODEL_DERIVED_HISTORICAL_SHADOW");
        assertThat(report.aggregate().entities()).extracting("key")
                .containsExactly("character_lin_xi","item_old_clock");
        assertThat(report.aggregate().facts()).singleElement().satisfies(fact->assertThat(fact.evidence())
                .extracting("quote").containsExactly("林砚握住旧钟","林溪走进钟表铺"));
        assertThat(report.aggregate().relations()).singleElement().satisfies(relation->{
            assertThat(relation.key()).isEqualTo("relation_linxi_clock");
            assertThat(relation.validFromChapter()).isEqualTo(1);
            assertThat(relation.sourceVersionIds()).containsExactly(first.approvedVersionId,last.approvedVersionId);
            assertThat(relation.evidence()).extracting("sourceVersionId")
                    .containsExactly(first.approvedVersionId,last.approvedVersionId);
        });
        assertThat(cached).isSameAs(report);
        assertThat(calls).hasValue(2);
        assertThat(novel.revision).isEqualTo(42);
        assertThat(first.approved().stateEntities).isEmpty();
        assertThat(last.approved().stateEntities).isEmpty();
        ArgumentCaptor<ModelGateway.Request> requests=ArgumentCaptor.forClass(ModelGateway.Request.class);
        verify(model,org.mockito.Mockito.times(2)).extractState(requests.capture(),any());
        assertThat(requests.getAllValues()).allSatisfy(request->assertThat(request.context().json())
                .contains("HISTORICAL_READ_ONLY_SHADOW","MODEL_DERIVED_NOT_CANON","STATE_EXTRACTOR"));
    }

    @Test void retriesOneInvalidEvidenceResultButNeverRelaxesValidation() {
        ModelGateway model=mock(ModelGateway.class);
        ObjectMapper mapper=new ObjectMapper();
        var service=new HistoricalStructuredMemoryShadowService(model,
                new RoleContextCompiler(mapper,new AgentContextPolicy()),new StateExtractionPolicy(),mapper,
                new SourceSnapshotFactory(),true,5);
        Novel novel=new Novel(); novel.id="novel-retry"; novel.title="重试"; novel.synopsis="测试"; novel.revision=1;
        Novel.Artifact chapter=chapter(1,"林砚打开旧钟后盖。"); novel.artifacts.add(chapter);
        when(model.ready()).thenReturn(true);
        var invalid=new ModelGateway.StateExtraction(List.of(new ModelGateway.ExtractedState(
                "event_open_clock","EVENT","林砚打开旧钟","ACTIVE",List.of("摘要中的错误引文"))));
        var factPart=new ModelGateway.StateExtraction(List.of(new ModelGateway.ExtractedState(
                "event_open_clock","EVENT","林砚打开旧钟","ACTIVE",List.of("林砚打开旧钟后盖"))),
                List.of(),List.of());
        var emptyFactPart=new ModelGateway.StateExtraction(List.of(),List.of(),List.of());
        var graphPart=new ModelGateway.StateExtraction(List.of(),List.of(),List.of());
        when(model.extractState(any(),any())).thenReturn(invalid,factPart,emptyFactPart,graphPart);

        var report=service.evaluate(novel,"retry-1",List.of(1));

        assertThat(report.status()).isEqualTo("SUCCEEDED");
        assertThat(report.modelCalls()).isEqualTo(4);
        assertThat(report.chapters()).singleElement().satisfies(result->{
            assertThat(result.attempts()).isEqualTo(2);
            assertThat(result.error()).isNull();
        });
        assertThat(report.aggregate().facts()).extracting("key").containsExactly("event_open_clock");
        assertThat(novel.revision).isEqualTo(1);
        ArgumentCaptor<ModelGateway.Request> requests=ArgumentCaptor.forClass(ModelGateway.Request.class);
        verify(model,org.mockito.Mockito.times(4)).extractState(requests.capture(),any());
        assertThat(requests.getAllValues().get(1).instructions()).contains("WORLD 和 CHARACTER");
        assertThat(requests.getAllValues().get(2).instructions()).contains("TIMELINE、EVENT 和 FORESHADOW");
        assertThat(requests.getAllValues().get(3).instructions()).contains("只返回 entities 和 relations");
    }

    @Test void retriesOnlyTheMalformedSegmentOnce() {
        ModelGateway model=mock(ModelGateway.class);
        ObjectMapper mapper=new ObjectMapper();
        var service=new HistoricalStructuredMemoryShadowService(model,
                new RoleContextCompiler(mapper,new AgentContextPolicy()),new StateExtractionPolicy(),mapper,
                new SourceSnapshotFactory(),true,5);
        Novel novel=new Novel(); novel.id="novel-segment-retry"; novel.title="分段重试";
        novel.synopsis="测试"; novel.revision=1;
        novel.artifacts.add(chapter(1,"林砚打开旧钟后盖。"));
        when(model.ready()).thenReturn(true);
        var invalid=new ModelGateway.StateExtraction(List.of(new ModelGateway.ExtractedState(
                "event_open_clock","EVENT","林砚打开旧钟","ACTIVE",List.of("错误引文"))));
        var empty=new ModelGateway.StateExtraction(List.of(),List.of(),List.of());
        var validFact=new ModelGateway.StateExtraction(List.of(new ModelGateway.ExtractedState(
                "event_open_clock","EVENT","林砚打开旧钟","ACTIVE",List.of("林砚打开旧钟后盖"))),
                List.of(),List.of());
        when(model.extractState(any(),any())).thenReturn(invalid).thenThrow(new Problem(502,"JSON 未闭合"))
                .thenReturn(empty,validFact,empty);

        var report=service.evaluate(novel,"segment-retry-1",List.of(1));

        assertThat(report.status()).isEqualTo("SUCCEEDED");
        assertThat(report.modelCalls()).isEqualTo(5);
        assertThat(report.aggregate().facts()).extracting("key").containsExactly("event_open_clock");
    }

    private Novel.Artifact chapter(int number,String content) {
        Novel.Artifact artifact=new Novel.Artifact(); artifact.kind=Novel.Kind.CHAPTER; artifact.chapterNumber=number;
        Novel.Version version=new Novel.Version(); version.title="第"+number+"章"; version.content=content;
        version.summary="章节"+number+"摘要"; artifact.versions.add(version); artifact.approvedVersionId=version.id;
        return artifact;
    }
}
