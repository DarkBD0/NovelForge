package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel;
import com.novelforge.shared.Problem;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

    @Test void causalAuditReadsOnlyValidatedFindingFields() {
        String json="""
                {"findings":[{"location":"第二章","problem":"年份没有依据",
                  "candidateQuote":"她把两个年份在心里摆了一下",
                  "basisQuote":"照片上没有日期",
                  "suggestion":"删除无依据的年份比较","severity":"必须修正",
                  "extraProviderField":{"unexpected":[1,2]}}],
                 "extraProviderSummary":{"confidence":0.8}}
                """;

        PlanCausalityAudit audit=reader.read(json,PlanCausalityAudit.class);

        assertThat(audit.findings()).singleElement().satisfies(finding->{
            assertThat(finding.problem()).isEqualTo("年份没有依据");
            assertThat(finding.severity()).isEqualTo("必须修正");
        });
        assertThatThrownBy(()->reader.read(json.replace("\"basisQuote\":\"照片上没有日期\"",
                        "\"basisQuote\":[\"照片上没有日期\"]"),PlanCausalityAudit.class))
                .isInstanceOf(Problem.class).hasMessageContaining("basisQuote 必须是非空文本");
    }

    @Test void acceptsLargeAtomicFactSplitButKeepsABoundedPatch() {
        String maximum=IntStream.range(0,ModelJsonReader.MAX_PATCH_OPERATIONS)
                .mapToObj(index->"{\"op\":\"DELETE_FACT\",\"key\":\"fact_"+index+"\"}")
                .collect(Collectors.joining(",","{\"operations\":[","]}"));
        assertThat(reader.read(maximum,RewritePatch.class).operations())
                .hasSize(ModelJsonReader.MAX_PATCH_OPERATIONS);

        String tooMany=IntStream.rangeClosed(0,ModelJsonReader.MAX_PATCH_OPERATIONS)
                .mapToObj(index->"{\"op\":\"DELETE_FACT\",\"key\":\"fact_"+index+"\"}")
                .collect(Collectors.joining(",","{\"operations\":[","]}"));
        assertThatThrownBy(()->reader.read(tooMany,RewritePatch.class))
                .isInstanceOf(Problem.class).hasMessageContaining("最多一百项");
    }

    @Test void rendersReadablePlanContentWhenProviderLeavesGenericContentEmpty() {
        String json="""
                {"title":"第一批章节规划","content":"","summary":"开篇建立委托关系","facts":[],
                 "plan":{"startChapter":1,"endChapter":1,"prepareNextAfterChapter":1,"finalBatch":true,
                   "triggerReason":"单章短篇无需下一批","handoff":"本章完成故事","assumptions":"",
                   "chapters":[{"number":1,"title":"钥匙","purpose":"周岚委托林澈调查旧站台",
                     "targetWords":1200,"sceneBeats":["周岚交付钥匙"],"revealBoundary":"不提前揭示真相",
                     "endingHook":"林澈进入旧站台"}]},"outlineSpec":null}
                """;

        ModelGateway.Generated generated=reader.read(json,ModelGateway.Generated.class);

        assertThat(generated.content()).contains("第1章至第1章章节规划","周岚委托林澈调查旧站台",
                "场景节点：","批次衔接：本章完成故事");
        assertThat(generated.plan()).isNotNull();
    }

    @Test void derivesPlanTitleAndEmptyFactsWithoutDiscardingValidPlan() {
        String json="""
                {"plan":{"startChapter":1,"endChapter":1,"prepareNextAfterChapter":1,"finalBatch":true,
                  "triggerReason":"短篇收束","handoff":"结局完成","assumptions":"",
                  "chapters":[{"number":1,"title":"旧照","purpose":"顾乔找到失踪者去向"}]}}
                """;

        ModelGateway.Generated generated=reader.read(json,ModelGateway.Generated.class);

        assertThat(generated.title()).isEqualTo("第1章至第1章章节规划");
        assertThat(generated.plan()).isNotNull();
        assertThat(generated.content()).contains("顾乔找到失踪者去向");
        assertThat(generated.summary()).contains("第1章旧照");
        assertThat(generated.facts()).isEmpty();
        assertThat(generated.draftIssues()).isEmpty();
    }

    @Test void acceptsRootPlanObjectButStillValidatesItsRequiredFields() {
        String json="""
                {"startChapter":1,"endChapter":1,"prepareNextAfterChapter":1,"finalBatch":true,
                 "triggerReason":"短篇收束","handoff":"结局完成","assumptions":"",
                 "chapters":[{"number":1,"title":"旧照","purpose":"顾乔找到失踪者去向"}]}
                """;

        assertThat(reader.read(json,ModelGateway.Generated.class).plan()).isNotNull();
        assertThatThrownBy(()->reader.read(json.replace("\"endChapter\":1,", ""),ModelGateway.Generated.class))
                .isInstanceOf(Problem.class).hasMessageContaining("plan.endChapter 必须是整数");
    }

    @Test void missingProseTitleKeepsDraftIncompleteRatherThanDiscardingContent() {
        ModelGateway.Generated generated=reader.read("{\"content\":\"她推开门，看见桌上的旧照。\",\"summary\":\"发现旧照\",\"facts\":[]}",
                ModelGateway.Generated.class);

        assertThat(generated.content()).contains("旧照");
        assertThat(generated.title()).isEqualTo("待命名草稿");
        assertThat(generated.draftIssues()).contains("标题缺失或为空，请补全标题后重新检查");
    }

    @Test void suppliesNeutralSuggestionForOtherwiseCompleteReviewFinding() {
        String json="""
                {"passed":false,"issues":[{"location":"当前正文","problem":"时间冲突",
                 "evidence":"候选与已确认时间互斥","suggestion":"","severity":"必须修正"}]}
                """;

        Novel.Review review=reader.read(json,Novel.Review.class);

        assertThat(review.issueDetails()).singleElement().satisfies(issue->
                assertThat(issue.suggestion()).isEqualTo("请由作者核对后修改"));
    }

    @Test void missingEmptyClaimChecksDoNotDiscardAValidChapterAudit() {
        ChapterEvidenceAudit audit=reader.read("{\"planChecks\":[]}",ChapterEvidenceAudit.class);
        assertThat(audit.planChecks()).isEmpty();
        assertThat(audit.claimChecks()).isEmpty();
        assertThatThrownBy(()->reader.read("{\"planChecks\":[],\"claimChecks\":\"wrong\"}",
                ChapterEvidenceAudit.class)).isInstanceOf(Problem.class).hasMessageContaining("claimChecks 必须是数组");
    }
}
