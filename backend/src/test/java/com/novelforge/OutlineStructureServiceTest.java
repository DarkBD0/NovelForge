package com.novelforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.generation.ModelGateway;
import com.novelforge.novel.Novel;
import com.novelforge.outline.OutlineRenderer;
import com.novelforge.outline.OutlineSpec;
import com.novelforge.outline.OutlineStructureService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutlineStructureServiceTest {
    private final OutlineRenderer renderer=new OutlineRenderer();
    private final OutlineStructureService service=new OutlineStructureService(renderer,new ObjectMapper());

    @Test void validSpecIsRenderedHashedAndAcceptedDeterministically() {
        Novel novel=novel(1000);
        OutlineSpec spec=OutlineTestFixtures.validSpec(1000,"从失踪线索推进到明确结局");
        ModelGateway.Generated normalized=service.normalizeGenerated(novel,
                new ModelGateway.Generated("全书大纲","模型自由展示文本","摘要",List.of(),null,spec,List.of()));
        Novel.Version stored=new Novel.Version(); stored.outlineSpec=spec; stored.outlineSpecHash=service.hash(spec);

        Novel.Review review=service.validate(novel,normalized,stored);

        assertThat(review.passed()).isTrue();
        assertThat(review.issueDetails()).isEmpty();
        assertThat(normalized.content()).isEqualTo(renderer.render(spec))
                .contains("故事核心","核心吸引点","明确结局")
                .doesNotContain("创作要求与约束","作者明确要求","硬性约束","创作假设","第1部分");
        assertThat(service.hash(spec)).hasSize(64).isEqualTo(service.hash(spec));
    }

    @Test void internalIdentityChangesDoNotCreateAnAuthorVisibleDifference() {
        OutlineSpec before=OutlineTestFixtures.validSpec(1000,"保持同一条主线");
        OutlineSpec after=OutlineTestFixtures.validSpec(1000,"保持同一条主线");
        after.parts.getFirst().id="part_internal_replaced";
        after.parts.getFirst().stages.getFirst().id="stage_internal_replaced";

        assertThat(service.hash(after)).isNotEqualTo(service.hash(before));
        assertThat(renderer.render(after)).isEqualTo(renderer.render(before));
    }

    @Test void functionalCharactersStayConciseWhileLegacyOutlinesRemainReadable() {
        OutlineSpec spec=OutlineTestFixtures.validSpec(1000,"保持主线");
        OutlineSpec.CharacterArc witness=new OutlineSpec.CharacterArc();
        witness.id="character-witness"; witness.name="门卫"; witness.roleTier="FUNCTIONAL_NPC";
        witness.storyFunction="在开篇交出失踪者最后一次出现的线索";
        spec.characters=new ArrayList<>(spec.characters); spec.characters.add(witness);
        ModelGateway.Generated normalized=service.normalizeGenerated(novel(1000),
                new ModelGateway.Generated("全书大纲","忽略","摘要",List.of(),null,spec,List.of()));

        assertThat(service.validate(novel(1000),normalized,null).passed()).isTrue();
        assertThat(normalized.content()).contains("## 功能性人物","门卫：在开篇交出失踪者最后一次出现的线索")
                .doesNotContain("不设独立目标","不设人物弧光");

        spec.schemaVersion="1"; spec.readerAttraction=null;
        ModelGateway.Generated legacy=service.normalizeGenerated(novel(1000),
                new ModelGateway.Generated("旧版大纲","忽略","摘要",List.of(),null,spec,List.of()));
        assertThat(service.validate(novel(1000),legacy,null).passed()).isTrue();
    }

    @Test void newOutlineNeedsAConcreteReaderAndDramaBridgeWithoutBreakingOldVersions() {
        OutlineSpec spec=OutlineTestFixtures.validSpec(1000,"主角为救父亲进入被封锁的灯塔");
        OutlineSpec.StoryStage stage=spec.parts.getFirst().stages.getFirst();
        spec.schemaVersion="3";
        ModelGateway.Generated missing=service.normalizeGenerated(novel(1000),
                new ModelGateway.Generated("全书大纲","忽略","摘要",List.of(),null,spec,List.of()));
        assertThat(service.validate(novel(1000),missing,null).issueDetails())
                .anyMatch(issue->issue.location().contains("阻力与抉择"));

        stage.readerPayoff="父亲能否在风暴前获救";
        stage.dramaticTurn="港务员为保住秘密阻止通行，主角冒失去修船资格的风险绕路救人";
        stage.payoffResult="父亲获救，秘密航线的证据被公开";
        stage.escalation="父子误解得到回应，港口开始追查责任人";
        ModelGateway.Generated complete=service.normalizeGenerated(novel(1000),
                new ModelGateway.Generated("全书大纲","忽略","摘要",List.of(),null,spec,List.of()));
        assertThat(service.validate(novel(1000),complete,null).passed()).isTrue();
        assertThat(complete.content()).contains("读者期待","阻力与抉择","阶段兑现","后续变化");

        spec.schemaVersion="2";
        stage.readerPayoff=null; stage.dramaticTurn=null; stage.payoffResult=null; stage.escalation=null;
        ModelGateway.Generated legacy=service.normalizeGenerated(novel(1000),
                new ModelGateway.Generated("旧版大纲","忽略","摘要",List.of(),null,spec,List.of()));
        assertThat(service.validate(novel(1000),legacy,null).passed()).isTrue();

        stage.escalation="下一阶段继续寻找证据";
        assertThat(renderer.render(spec)).contains("下一步升级：下一阶段继续寻找证据")
                .doesNotContain("后续变化：下一阶段继续寻找证据");
    }

    @Test void realisticStoryCanOmitSpecialWorldRulesWithoutShowingAnEmptySection() {
        OutlineSpec spec=OutlineTestFixtures.validSpec(1000,"主角开店并解决与旧雇主的冲突");
        spec.worldRules=List.of();
        ModelGateway.Generated normalized=service.normalizeGenerated(novel(1000),
                new ModelGateway.Generated("全书大纲","忽略","摘要",List.of(),null,spec,List.of()));

        assertThat(service.validate(novel(1000),normalized,null).passed()).isTrue();
        assertThat(normalized.content()).doesNotContain("## 世界与核心规则");
    }

    @Test void invalidBudgetDuplicateIdAndBrokenReferenceAreBlockingIssues() {
        Novel novel=novel(1000);
        OutlineSpec spec=OutlineTestFixtures.validSpec(1000,"存在结构错误的主线");
        spec.parts.getFirst().estimatedWords=900;
        spec.parts.getFirst().stages.getFirst().estimatedWords=800;
        spec.foreshadows.getFirst().recoveryStageId="stage-missing";
        OutlineSpec.Constraint duplicate=new OutlineSpec.Constraint();
        duplicate.id="rule-evidence"; duplicate.category="HARD"; duplicate.text="重复编号";
        spec.constraints=new ArrayList<>(spec.constraints); spec.constraints.add(duplicate);
        ModelGateway.Generated normalized=service.normalizeGenerated(novel,
                new ModelGateway.Generated("全书大纲","忽略","摘要",List.of(),null,spec,List.of()));

        Novel.Review review=service.validate(novel,normalized,null);

        assertThat(review.passed()).isFalse();
        assertThat(review.issueDetails()).extracting(Novel.ReviewIssue::problem)
                .contains("阶段字数之和与篇章预算不一致","各篇章预算之和与小说目标字数不一致",
                        "回收阶段引用不存在","稳定编号重复");
    }

    @Test void displayTextCannotDriftAwayFromOutlineSpec() {
        Novel novel=novel(1000);
        OutlineSpec spec=OutlineTestFixtures.validSpec(1000,"稳定主线");
        ModelGateway.Generated drifted=new ModelGateway.Generated("全书大纲","另一份可独立修改的展示正文",
                "摘要",List.of(),null,spec,List.of());

        Novel.Review review=service.validate(novel,drifted,null);

        assertThat(review.passed()).isFalse();
        assertThat(review.issueDetails()).anySatisfy(issue->{
            assertThat(issue.location()).isEqualTo("作者可读大纲");
            assertThat(issue.problem()).isEqualTo("展示文本不是由当前结构化大纲生成");
        });
    }

    @Test void authorVisibleOutlineRejectsEditingProcessLanguage() {
        OutlineSpec spec=OutlineTestFixtures.validSpec(1000,"全书不分卷，只保留一条主线和四个紧凑阶段");
        ModelGateway.Generated normalized=service.normalizeGenerated(novel(1000),
                new ModelGateway.Generated("全书大纲","忽略","摘要",List.of(),null,spec,List.of()));

        Novel.Review review=service.validate(novel(1000),normalized,null);

        assertThat(review.passed()).isFalse();
        assertThat(review.issueDetails()).anyMatch(issue->"内部工作术语混入大纲正文".equals(issue.problem()));
    }

    private Novel novel(long words) {
        Novel novel=new Novel(); novel.targetWords=words; return novel;
    }
}
