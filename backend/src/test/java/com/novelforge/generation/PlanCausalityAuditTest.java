package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel.ChapterBeat;
import com.novelforge.novel.Novel.Plan;
import com.novelforge.novel.Novel.Review;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanCausalityAuditTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void blocksGroundedContradictionInPlanBeforeChapterWriting() throws Exception {
        Plan plan=plan("第六章，陈小满在火灾中死亡", "第七章，火后拍下的合照中出现陈小满");
        PlanCausalityAudit audit=new PlanCausalityAudit(List.of(new PlanCausalityAudit.Finding(
                "第七章","死亡人物出现在死后新拍的合照中","火后拍下的合照中出现陈小满",
                "陈小满在火灾中死亡","改为火灾前拍摄的合照，或修改人物身份与拍摄时间","必须修正")));

        Review result=audit.merge(clear(),generated(plan),mapper.readTree("{}"));

        assertThat(result.passed()).isFalse();
        assertThat(result.issueDetails()).hasSize(1);
        assertThat(result.issueDetails().getFirst().evidence()).contains("火后拍下的合照中出现陈小满","陈小满在火灾中死亡");
    }

    @Test void groundsUnverifiableClueAgainstTheSameChaptersGoal() throws Exception {
        Plan plan=new Plan(); plan.startChapter=4; plan.endChapter=5;
        plan.chapters=List.of(new ChapterBeat(4,"线索","顾乔获得可独立核实的具体去向",900,
                List.of("邻居只记得林秋宁投奔南边亲戚，具体城市不确定"),"",""),
                new ChapterBeat(5,"寻找","顾乔直接根据邻居提供的地点找到地址"));
        PlanCausalityAudit audit=new PlanCausalityAudit(List.of(new PlanCausalityAudit.Finding(
                "第四章","章目标和可用线索不匹配","顾乔获得可独立核实的具体去向",
                "具体城市不确定","给出可核实的迁出登记或另一个具体途径","必须修正")));

        Review result=audit.merge(clear(),generated(plan),mapper.readTree("{}"));

        assertThat(result.passed()).isFalse();
        assertThat(result.issueDetails()).singleElement().satisfies(issue->
                assertThat(issue.evidence()).contains("可独立核实的具体去向","具体城市不确定"));
    }

    @Test void finalPlanCanUseConfirmedOutlineEndingAsEvidence() throws Exception {
        Plan plan=new Plan(); plan.startChapter=5; plan.endChapter=5; plan.finalBatch=true;
        plan.chapters=List.of(new ChapterBeat(5,"和解","顾乔决定以后再找林秋宁，先向邻居道歉"));
        var context=mapper.readTree("{\"planStoryEngine\":{\"endingContract\":\"顾乔查清林秋宁的具体去向\"}}");
        var audit=new PlanCausalityAudit(List.of(new PlanCausalityAudit.Finding(
                "最终章","大纲要求的核心去向仍未查明","顾乔决定以后再找林秋宁",
                "顾乔查清林秋宁的具体去向","在最终章安排可信的核实行动或修改最终批次","必须修正")));

        Review result=audit.merge(clear(),generated(plan),context);

        assertThat(result.passed()).isFalse();
        assertThat(result.issueDetails()).singleElement().satisfies(issue->
                assertThat(issue.evidence()).contains("以后再找林秋宁","查清林秋宁的具体去向"));
    }

    @Test void doesNotBlockPreFirePhotoOrInventMissingHandoff() throws Exception {
        Plan plan=plan("第六章，陈小满在火灾中死亡", "第七章，找到火灾前拍下的合照");
        PlanCausalityAudit audit=new PlanCausalityAudit(List.of(new PlanCausalityAudit.Finding(
                "第七章","照片时间冲突","火后拍下的合照中出现陈小满",
                "陈小满在火灾中死亡","补写解释","必须修正")));

        Review result=audit.merge(clear(),generated(plan),mapper.readTree("{}"));

        assertThat(result.passed()).isTrue();
        assertThat(result.issueDetails()).isEmpty();
    }

    @Test void acceptsConfirmedSourceQuoteButAdvisoryDoesNotBlock() throws Exception {
        Plan plan=plan("第七章，拿到名单后去找老吴", "第八章，名单上的地址已经拆迁");
        PlanCausalityAudit audit=new PlanCausalityAudit(List.of(new PlanCausalityAudit.Finding(
                "第七章","名单来源可以稍微澄清","拿到名单后去找老吴",
                "老吴答应明天给名单","如有需要，调整为先去找老吴再拿名单","作者决定")));
        var context=mapper.readTree("{\"authoritativeReferences\":[{\"summary\":\"老吴答应明天给名单\"}]}");

        Review result=audit.merge(clear(),generated(plan),context);

        assertThat(result.passed()).isTrue();
        assertThat(result.issueDetails()).hasSize(1);
        assertThat(result.issueDetails().getFirst().severity()).isEqualTo("作者决定");
    }

    @Test void collapsesRestatementOfSameCauseWithinOneChapter() throws Exception {
        Plan plan=new Plan(); plan.startChapter=1; plan.endChapter=2;
        plan.chapters=List.of(
                new ChapterBeat(1,"旧照","照片肯定拍在火灾前",null,
                        List.of("她断定旧照片拍于火灾之前"),"",""),
                new ChapterBeat(2,"火灾","她第一次得知楼里曾发生火灾"));
        PlanCausalityAudit audit=new PlanCausalityAudit(List.of(
                new PlanCausalityAudit.Finding("第一章","过早知道火灾","照片肯定拍在火灾前",
                        "她第一次得知楼里曾发生火灾","先建立火灾信息来源","必须修正"),
                new PlanCausalityAudit.Finding("第一章场景","同一问题的另一种写法","她断定旧照片拍于火灾之前",
                        "照片肯定拍在火灾前","改为尚不确定拍摄时间","必须修正")));

        Review result=audit.merge(clear(),generated(plan),mapper.readTree("{}"));

        assertThat(result.passed()).isFalse();
        assertThat(result.issueDetails()).hasSize(1);
        assertThat(result.issueDetails().getFirst().problem()).isEqualTo("过早知道火灾");
    }

    @Test void retainsIndependentCrossChapterFindingAfterDeduplication() throws Exception {
        Plan plan=new Plan(); plan.startChapter=1; plan.endChapter=3;
        plan.chapters=List.of(
                new ChapterBeat(1,"旧照","照片肯定拍在火灾前",null,
                        List.of("她断定旧照片拍于火灾之前"),"",""),
                new ChapterBeat(2,"火灾","她第一次得知楼里曾发生火灾，库房钥匙仍在失踪者手中"),
                new ChapterBeat(3,"钥匙","她用尚未拿到的钥匙打开库房"));
        PlanCausalityAudit audit=new PlanCausalityAudit(List.of(
                new PlanCausalityAudit.Finding("第一章","过早知道火灾","照片肯定拍在火灾前",
                        "她第一次得知楼里曾发生火灾","先建立来源","必须修正"),
                new PlanCausalityAudit.Finding("第一章场景","同一问题的另一种写法","她断定旧照片拍于火灾之前",
                        "照片肯定拍在火灾前","改为尚不确定","必须修正"),
                new PlanCausalityAudit.Finding("第三章","钥匙尚未取得","她用尚未拿到的钥匙打开库房",
                        "库房钥匙仍在失踪者手中","先取得钥匙","必须修正")));

        Review result=audit.merge(clear(),generated(plan),mapper.readTree("{}"));

        assertThat(result.issueDetails()).hasSize(2);
        assertThat(result.issueDetails()).extracting("problem")
                .containsExactly("过早知道火灾","钥匙尚未取得");
    }

    private Plan plan(String first,String second) {
        Plan plan=new Plan(); plan.startChapter=6; plan.endChapter=7;
        plan.chapters=List.of(new ChapterBeat(6,"旧事",first),new ChapterBeat(7,"照片",second));
        return plan;
    }
    private ModelGateway.Generated generated(Plan plan) {
        return new ModelGateway.Generated("章节规划",PlanNarrativeRenderer.render(plan),"摘要",List.of(),plan);
    }
    private Review clear() { return new Review(true,List.of(),false,false,false,List.of()); }
}
