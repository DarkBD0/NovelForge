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
        assertThat(normalized.content()).isEqualTo(renderer.render(spec)).contains("故事核心","明确结局");
        assertThat(service.hash(spec)).hasSize(64).isEqualTo(service.hash(spec));
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

    private Novel novel(long words) {
        Novel novel=new Novel(); novel.targetWords=words; return novel;
    }
}
