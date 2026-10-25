package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.ChapterBeat;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Plan;
import com.novelforge.novel.Novel.ResolutionStep;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.Version;
import com.novelforge.outline.OutlineSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EndingGoalPlanAuditTest {
    private final Review passed=new Review(true,List.of(),false,false,false);

    @Test void optionalFieldsDoNotChangeSerializedLegacyContracts() throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        assertThat(mapper.writeValueAsString(new OutlineSpec())).doesNotContain("endingGoals");
        assertThat(mapper.writeValueAsString(new Plan())).doesNotContain("resolutionSteps");
    }

    @Test void finalPlanMustMapEveryApprovedGoalToAnExistingChapter() {
        Novel novel=novelWithGoals();
        Plan plan=plan(true);
        plan.resolutionSteps=List.of(new ResolutionStep("where",9,"追查记录","登记记录","查明具体去向","记录仅证明登记结果"));

        Review result=EndingGoalPlanAudit.apply(novel,null,Action.PLAN,candidate(plan),passed);

        assertThat(result.passed()).isFalse();
        assertThat(result.issueDetails()).anySatisfy(issue->assertThat(issue.problem()).contains("章节没有对应"));
        assertThat(result.issueDetails()).anySatisfy(issue->assertThat(issue.problem()).contains("主角是否决定继续"));
    }

    @Test void completeMappingPassesWithoutRequiringOneSpecificGenreOfEvidence() {
        Novel novel=novelWithGoals();
        Plan plan=plan(true);
        plan.resolutionSteps=List.of(
                new ResolutionStep("where",2,"查证并联系本人","本人回应","知道她已迁居的城市","不能推断详细住址"),
                new ResolutionStep("choice",2,"主角作出选择","人物行动与后果","决定是否继续","不要求大团圆"));

        assertThat(EndingGoalPlanAudit.apply(novel,null,Action.PLAN,candidate(plan),passed).passed()).isTrue();
    }

    @Test void oldOutlineUsesItsEndingContractAndNonFinalPlanIsNotForcedToResolveIt() {
        Novel novel=novelWithGoals();
        novel.artifacts.get(0).approved().outlineSpec.endingGoals=List.of();
        Plan plan=plan(true);
        Review missing=EndingGoalPlanAudit.apply(novel,null,Action.PLAN,candidate(plan),passed);
        assertThat(missing.issueDetails()).singleElement().satisfies(issue->
                assertThat(issue.problem()).contains("找到姐姐并明确她的选择"));
        plan.finalBatch=false;
        assertThat(EndingGoalPlanAudit.apply(novel,null,Action.PLAN,candidate(plan),passed).passed()).isTrue();
    }

    private Novel novelWithGoals() {
        Novel novel=new Novel();
        Artifact outline=new Artifact(); outline.kind=Kind.OUTLINE;
        Version version=new Version(); version.outlineSpec=new OutlineSpec();
        version.outlineSpec.endingContract="找到姐姐并明确她的选择";
        OutlineSpec.EndingGoal where=new OutlineSpec.EndingGoal();
        where.id="where"; where.goal="查清姐姐的具体去向"; where.proofStandard="可信来源足以确定她所在城市";
        OutlineSpec.EndingGoal choice=new OutlineSpec.EndingGoal();
        choice.id="choice"; choice.goal="主角是否决定继续"; choice.proofStandard="正文呈现选择及后果";
        version.outlineSpec.endingGoals=List.of(where,choice);
        outline.versions.add(version); outline.approvedVersionId=version.id; novel.artifacts.add(outline);
        return novel;
    }

    private Plan plan(boolean finalBatch) {
        Plan plan=new Plan(); plan.startChapter=1; plan.endChapter=2; plan.finalBatch=finalBatch;
        plan.chapters=List.of(new ChapterBeat(1,"线索","继续调查"),new ChapterBeat(2,"结局","主角得知答案"));
        return plan;
    }

    private ModelGateway.Generated candidate(Plan plan) {
        return new ModelGateway.Generated("规划","正文","摘要",List.of(),plan);
    }
}
