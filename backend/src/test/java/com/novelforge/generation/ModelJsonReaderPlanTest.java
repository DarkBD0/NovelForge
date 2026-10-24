package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.ChapterBeat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelJsonReaderPlanTest {
    @Test void planDiscardsMalformedFactFragmentsBeforeValidation() throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        Novel.Plan plan=new Novel.Plan(); plan.startChapter=1; plan.endChapter=1;
        plan.prepareNextAfterChapter=1; plan.finalBatch=true;
        plan.triggerReason="故事结束"; plan.handoff="主线收束"; plan.assumptions="";
        plan.chapters=List.of(new ChapterBeat(1,"选择","主角作出最终选择"));
        var envelope=mapper.createObjectNode();
        envelope.put("title","规划"); envelope.put("content","占位"); envelope.put("summary","摘要");
        envelope.set("plan",mapper.valueToTree(plan));
        envelope.set("facts",mapper.valueToTree(List.of(Map.of("detail","模型误写的无键档案"))));

        ModelGateway.Generated result=new ModelJsonReader(mapper).read(
                mapper.writeValueAsString(envelope),ModelGateway.Generated.class);

        assertThat(result.plan()).isNotNull();
        assertThat(result.facts()).isEmpty();
    }

    @Test void nonPlanContentStillRejectsMalformedFacts() {
        String json="{\"title\":\"人物设定\",\"content\":\"人物内容\",\"summary\":\"摘要\","
                +"\"facts\":[{\"detail\":\"缺少键的条目\"}],\"plan\":null}";

        assertThatThrownBy(()->new ModelJsonReader(new ObjectMapper()).read(json,ModelGateway.Generated.class))
                .hasMessageContaining("key 必须是非空文本");
    }

    @Test void modelPlanCannotRetainAnOldNarrativeOrSummary() throws Exception {
        Novel.Plan plan=new Novel.Plan(); plan.startChapter=1; plan.endChapter=2;
        plan.prepareNextAfterChapter=1; plan.triggerReason="同行争抢";
        plan.handoff="第一份录用后继续争抢"; plan.assumptions="新公司尚未到场";
        plan.chapters=List.of(new ChapterBeat(1,"走进报告厅","受挫后争取临时面试"),
                new ChapterBeat(2,"第一份录用","当场拿到第一份录用"));
        ObjectMapper mapper=new ObjectMapper();
        ModelGateway.Generated stale=new ModelGateway.Generated("新规划","第一章查手机，第二章去就业中心",
                "全书仍在调查",List.of(),plan);

        ModelGateway.Generated result=new ModelJsonReader(mapper).read(mapper.writeValueAsString(stale),
                ModelGateway.Generated.class);

        assertThat(result.content()).contains("第1章 走进报告厅","第2章 第一份录用")
                .doesNotContain("查手机","就业中心");
        assertThat(result.summary()).contains("第2章第一份录用").doesNotContain("全书仍在调查");
    }

    @Test void denseChapterCanHaveFiveSceneBeatsButNotAnUnboundedList() throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        ModelJsonReader reader=new ModelJsonReader(mapper);
        Novel.Plan plan=new Novel.Plan();
        plan.startChapter=1; plan.endChapter=1; plan.prepareNextAfterChapter=1;
        plan.finalBatch=true; plan.triggerReason="进入高潮";
        plan.handoff="完成本章"; plan.assumptions="无";
        plan.chapters=List.of(new ChapterBeat(1,"同场竞争","多方竞争后作出选择",2400,
                IntStream.range(0,5).mapToObj(i->"场景节点"+(i+1)).toList(),"不提前揭示结局","作出选择"));
        ModelGateway.Generated generated=new ModelGateway.Generated("规划","占位内容","摘要",List.of(),plan);

        ModelGateway.Generated accepted=reader.read(mapper.writeValueAsString(generated),ModelGateway.Generated.class);
        assertThat(accepted.plan().chapters.getFirst().sceneBeats()).hasSize(5);

        plan.chapters=List.of(new ChapterBeat(1,"同场竞争","多方竞争后作出选择",2400,
                IntStream.range(0,9).mapToObj(i->"场景节点"+(i+1)).toList(),"不提前揭示结局","作出选择"));
        assertThatThrownBy(()->reader.read(mapper.writeValueAsString(generated),ModelGateway.Generated.class))
                .hasMessageContaining("最多包含8个场景节点");
    }

    @Test void keyStateLedgerIsStoredButNotTurnedIntoReaderFacingPlot() throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        Novel.Plan plan=new Novel.Plan(); plan.startChapter=1; plan.endChapter=1;
        plan.prepareNextAfterChapter=1; plan.finalBatch=true;
        plan.triggerReason="全书收束";plan.handoff="结局明确";plan.assumptions="无";
        plan.chapters=List.of(new ChapterBeat(1,"旧照片","主角找到火灾前的合照"));
        plan.criticalTransitions=List.of(new Novel.CriticalTransition(1,"合照","尚未发现",
                "找到火灾前拍摄的合照","合照在主角手里","正文发生","火灾前拍摄",
                "只能证明拍摄当天在场，不能证明火灾当晚也在场"));
        var generated=new ModelGateway.Generated("规划","旧内容","旧摘要",List.of(),plan);

        var accepted=new ModelJsonReader(mapper).read(mapper.writeValueAsString(generated),ModelGateway.Generated.class);

        assertThat(accepted.plan().criticalTransitions).hasSize(1);
        assertThat(accepted.plan().criticalTransitions.getFirst().inferenceBoundary())
                .contains("不能证明火灾当晚也在场");
        assertThat(accepted.content()).contains("主角找到火灾前的合照")
                .doesNotContain("尚未发现","合照在主角手里");
        assertThat(accepted.summary()).doesNotContain("尚未发现");

        plan.criticalTransitions=List.of(new Novel.CriticalTransition(1,"合照","尚未发现",
                "找到合照","已取得","错误状态","火灾前拍摄"));
        var recovered=new ModelJsonReader(mapper).read(mapper.writeValueAsString(generated),
                ModelGateway.Generated.class);
        assertThat(recovered.plan().criticalTransitions).isEmpty();
        assertThat(recovered.content()).contains("主角找到火灾前的合照");
    }

    @Test void malformedInternalLedgerDoesNotDestroyAPlanRewritePatch() throws Exception {
        ObjectMapper mapper=new ObjectMapper();Novel.Plan plan=new Novel.Plan();
        plan.startChapter=1;plan.endChapter=1;plan.prepareNextAfterChapter=1;plan.finalBatch=true;
        plan.triggerReason="结束";plan.handoff="完整结局";plan.assumptions="无";
        plan.chapters=List.of(new ChapterBeat(1,"结局","主角公开证据"));
        plan.criticalTransitions=List.of(new Novel.CriticalTransition(1,"证据","尚未取得",
                "取得证据","已取得","无效值","不适用"));
        String json=mapper.writeValueAsString(Map.of("operations",List.of(Map.of("op","SET_PLAN","plan",plan))));

        RewritePatch patch=new ModelJsonReader(mapper).read(json,RewritePatch.class);

        assertThat(patch.operations()).hasSize(1);
        assertThat(patch.operations().getFirst().plan().chapters).hasSize(1);
        assertThat(patch.operations().getFirst().plan().criticalTransitions).isEmpty();
    }
}
