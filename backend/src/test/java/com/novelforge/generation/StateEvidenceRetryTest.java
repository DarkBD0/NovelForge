package com.novelforge.generation;

import com.novelforge.shared.Problem;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateEvidenceRetryTest {
    private final StateExtractionPolicy policy=new StateExtractionPolicy();
    private final ModelGateway.Generated chapter=new ModelGateway.Generated("第一章",
            "林渡说，实习时理过工装清单。", "林渡说明经历",List.of(),null);
    private final ModelGateway.Request request=new ModelGateway.Request(null,null,null,null,"只提取已发生状态");

    @Test void retriesOnceWithVerbatimFeedbackAndPersistsOnlyRealEvidence() {
        AtomicInteger calls=new AtomicInteger();
        var validated=StateEvidenceRetry.extractAndValidate(r->{
            if(calls.incrementAndGet()==1) {
                return state("林渡说，实习时理了工装清单。");
            }
            assertThat(r.instructions()).contains("理了工装清单").contains("原样复制");
            return state("林渡说，实习时理过工装清单。");
        },request,chapter,policy);
        assertThat(calls).hasValue(2);
        assertThat(validated.evidence()).singleElement().satisfies(e->
                assertThat(e.evidenceQuotes()).containsExactly("林渡说，实习时理过工装清单。"));
    }

    @Test void stillRejectsFabricatedEvidenceAfterTheOnlyRetry() {
        AtomicInteger calls=new AtomicInteger();
        assertThatThrownBy(()->StateEvidenceRetry.extractAndValidate(r->{
            calls.incrementAndGet(); return state("林渡获得了工厂经理职位。");
        },request,chapter,policy)).isInstanceOf(Problem.class).hasMessageContaining("并非正文原文");
        assertThat(calls).hasValue(2);
    }

    @Test void retriesMalformedOptionalRelationWithoutChangingChapter() {
        AtomicInteger calls=new AtomicInteger();
        var validated=StateEvidenceRetry.extractAndValidate(r->{
            if(calls.incrementAndGet()==1) return new ModelGateway.StateExtraction(List.of(),List.of(
                    new ModelGateway.ExtractedEntity("character_lin_du","CHARACTER","林渡",List.of(),"人物",List.of("林渡说")),
                    new ModelGateway.ExtractedEntity("character_notebook","CHARACTER","工装清单",List.of(),"清单",List.of("工装清单"))),List.of(
                    new ModelGateway.ExtractedRelation("relation_lin_du_custodian_notebook","character_lin_du",
                            "CUSTODIAN_OF","character_notebook","林渡保管清单","ACTIVE",List.of("林渡说，实习时理过工装清单。"))));
            assertThat(r.instructions()).contains("CUSTODIAN_OF").contains("删除这条关系");
            return new ModelGateway.StateExtraction(List.of(),List.of(),List.of());
        },request,chapter,policy);
        assertThat(calls).hasValue(2);
        assertThat(validated.relations()).isEmpty();
    }

    @Test void retriesEntityTypeUsedAsFactType() {
        AtomicInteger calls=new AtomicInteger();
        var validated=StateEvidenceRetry.extractAndValidate(r->{
            if(calls.incrementAndGet()==1)
                return new ModelGateway.StateExtraction(List.of(new ModelGateway.ExtractedState(
                        "fact_lin_du_experience","CONCEPT","林渡整理清单","ACTIVE",
                        List.of("林渡说，实习时理过工装清单。"))));
            assertThat(r.instructions()).contains("CONCEPT 只能用于 entities").contains("删除该事实");
            return state("林渡说，实习时理过工装清单。");
        },request,chapter,policy);
        assertThat(calls).hasValue(2);
        assertThat(validated.facts()).hasSize(1);
    }

    @Test void retriesTooShortRelationEvidenceWithoutRegeneratingChapter() {
        AtomicInteger calls=new AtomicInteger();
        var result=StateEvidenceRetry.extractWithDiagnostics(r->{
            if(calls.incrementAndGet()==1) return relationState("林");
            assertThat(r.instructions()).contains("搜索该条目的主体名和动作关键词")
                    .contains("不要改动正文","本地按条目关键词检索到以下原文候选句","林渡说，实习时理过工装清单。");
            return relationState("林渡说，实习时理过工装清单。");
        },request,chapter,policy);
        assertThat(calls).hasValue(2);
        assertThat(result.state().relations()).hasSize(1);
        assertThat(result.warning()).isNull();
    }

    @Test void dropsOnlyInvalidShortEvidenceAfterBoundedRetry() {
        AtomicInteger calls=new AtomicInteger();
        var result=StateEvidenceRetry.extractWithDiagnostics(r->{
            calls.incrementAndGet(); return relationState("林");
        },request,chapter,policy);
        assertThat(calls).hasValue(2);
        assertThat(result.state().facts()).hasSize(1);
        assertThat(result.state().entities()).hasSize(2);
        assertThat(result.state().relations()).isEmpty();
        assertThat(result.warning()).contains("已舍弃").contains("可单独重试");
    }

    @Test void focusedRetryKeepsValidFirstPassEntriesWhenSecondOnlyRepairsOneItem() {
        AtomicInteger calls=new AtomicInteger();
        var result=StateEvidenceRetry.extractWithDiagnostics(r->{
            if(calls.incrementAndGet()==1) return new ModelGateway.StateExtraction(List.of(
                    new ModelGateway.ExtractedState("fact_first","CHARACTER","林渡做过清单","ACTIVE",
                            List.of("林渡说，实习时理过工装清单。")),
                    new ModelGateway.ExtractedState("fact_second","EVENT","林渡说过往事","ACTIVE",List.of("林"))));
            assertThat(r.instructions()).contains("优先修复该编号");
            return new ModelGateway.StateExtraction(List.of(new ModelGateway.ExtractedState(
                    "fact_second","EVENT","林渡说过往事","ACTIVE",
                    List.of("林渡说，实习时理过工装清单。"))));
        },request,chapter,policy);
        assertThat(calls).hasValue(2);
        assertThat(result.state().facts()).extracting(fact->fact.key())
                .containsExactly("fact_first","fact_second");
    }

    @Test void retriesOneMalformedJsonResponseWithoutRegeneratingChapter() {
        AtomicInteger calls=new AtomicInteger();
        var validated=StateEvidenceRetry.extractAndValidate(r->{
            if(calls.incrementAndGet()==1)
                throw new Problem(502,"模型返回多个 JSON 片段，无法确定修订版本");
            assertThat(r.instructions()).contains("只输出一个完整 JSON 对象");
            return state("林渡说，实习时理过工装清单。");
        },request,chapter,policy);
        assertThat(calls).hasValue(2);
        assertThat(validated.facts()).hasSize(1);
    }

    @Test void retriesMissingRelationEndpointFieldWithoutRewritingProse() {
        AtomicInteger calls=new AtomicInteger();
        var result=StateEvidenceRetry.extractAndValidate(r->{
            if(calls.incrementAndGet()==1)
                throw new Problem(502,"模型 JSON 缺少必要字段或类型错误：toEntityKey 必须是非空文本");
            assertThat(r.instructions()).contains("fromEntityKey 和 toEntityKey","不能确定关系两端时删除该关系");
            return state("林渡说，实习时理过工装清单。");
        },request,chapter,policy);
        assertThat(calls).hasValue(2);
        assertThat(result.facts()).hasSize(1);
    }

    @Test void warnsWhenSecondResponseSilentlyDropsFailedEntry() {
        AtomicInteger calls=new AtomicInteger();
        var result=StateEvidenceRetry.extractWithDiagnostics(r->{
            if(calls.incrementAndGet()==1) return relationState("林");
            return new ModelGateway.StateExtraction(List.of(),List.of(),List.of());
        },request,chapter,policy);
        assertThat(calls).hasValue(2);
        assertThat(result.state().relations()).isEmpty();
        assertThat(result.warning()).contains("已舍弃");
    }

    @Test void doesNotRetryNonFormatProviderFailure() {
        AtomicInteger calls=new AtomicInteger();
        assertThatThrownBy(()->StateEvidenceRetry.extractAndValidate(r->{
            calls.incrementAndGet(); throw new Problem(502,"模型最终内容为空");
        },request,chapter,policy)).isInstanceOf(Problem.class).hasMessageContaining("最终内容为空");
        assertThat(calls).hasValue(1);
    }

    @Test void preservesVerifiableStateWhenOneQuotationRemainsFabricatedAfterRetry() {
        AtomicInteger calls=new AtomicInteger();
        var result=StateEvidenceRetry.extractWithDiagnostics(r->{
            calls.incrementAndGet();
            return new ModelGateway.StateExtraction(List.of(
                    new ModelGateway.ExtractedState("fact_lie","EVENT","林渡已任工厂经理","ACTIVE",
                            List.of("林渡已任工厂经理")),
                    new ModelGateway.ExtractedState("fact_experience","CHARACTER","林渡整理过工装清单","ACTIVE",
                            List.of("林渡说，实习时理过工装清单。"))));
        },request,chapter,policy);
        assertThat(calls).hasValue(2);
        assertThat(result.state().facts()).singleElement().satisfies(fact->
                assertThat(fact.key()).isEqualTo("fact_experience"));
        assertThat(result.warning()).contains("已舍弃").contains("可单独重试");
    }

    private ModelGateway.StateExtraction state(String quote) {
        return new ModelGateway.StateExtraction(List.of(new ModelGateway.ExtractedState(
                "fact_lin_du_experience","CHARACTER","林渡实习时理过工装清单","ACTIVE",List.of(quote))));
    }

    private ModelGateway.StateExtraction relationState(String quote) {
        return new ModelGateway.StateExtraction(List.of(new ModelGateway.ExtractedState(
                "fact_lin_du_experience","CHARACTER","林渡实习时理过工装清单","ACTIVE",
                List.of("林渡说，实习时理过工装清单。"))),List.of(
                new ModelGateway.ExtractedEntity("character_lin_du","CHARACTER","林渡",List.of(),"人物",List.of("林渡")),
                new ModelGateway.ExtractedEntity("item_work_list","ITEM","工装清单",List.of(),"物品",List.of("工装清单"))),
                List.of(new ModelGateway.ExtractedRelation("relation_lin_du_related_work_list",
                        "character_lin_du","RELATED_TO","item_work_list","林渡提及工装清单", "ACTIVE",List.of(quote))));
    }
}
