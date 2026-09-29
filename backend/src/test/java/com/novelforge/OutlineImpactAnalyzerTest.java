package com.novelforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.outline.OutlineImpactAnalyzer;
import com.novelforge.outline.OutlineSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class OutlineImpactAnalyzerTest {
    private final OutlineImpactAnalyzer analyzer=new OutlineImpactAnalyzer(new ObjectMapper());

    @Test void worldRuleOnlyNeedsContinuityRecheck() {
        OutlineSpec before=OutlineTestFixtures.validSpec(1000,"主线");
        OutlineSpec after=OutlineTestFixtures.validSpec(1000,"主线");
        after.worldRules.getFirst().rule="修订后的世界硬规则";

        var scope=analyzer.analyze(before,after);

        assertThat(scope.continuityRequired()).isTrue();
        assertThat(scope.plotRequired()).isFalse();
        assertThat(scope.changedSections()).containsExactly("世界规则");
    }

    @Test void foreshadowOnlyNeedsPlotRecheck() {
        OutlineSpec before=OutlineTestFixtures.validSpec(1000,"主线");
        OutlineSpec after=OutlineTestFixtures.validSpec(1000,"主线");
        after.foreshadows.getFirst().clue="修订后的伏笔";

        var scope=analyzer.analyze(before,after);

        assertThat(scope.continuityRequired()).isFalse();
        assertThat(scope.plotRequired()).isTrue();
        assertThat(scope.changedSections()).containsExactly("伏笔");
    }

    @Test void eventChangeConservativelyRechecksBothAuditors() {
        OutlineSpec before=OutlineTestFixtures.validSpec(1000,"主线");
        OutlineSpec after=OutlineTestFixtures.validSpec(1000,"主线");
        after.parts.getFirst().stages.getFirst().events.getFirst().event="修订后的关键事件";

        var scope=analyzer.analyze(before,after);

        assertThat(scope.continuityRequired()).isTrue();
        assertThat(scope.plotRequired()).isTrue();
        assertThat(scope.changedSections()).containsExactly("篇章、阶段或事件");
    }

    @Test void assumptionOnlyReusesBothProfessionalReports() {
        OutlineSpec before=OutlineTestFixtures.validSpec(1000,"主线");
        OutlineSpec after=OutlineTestFixtures.validSpec(1000,"主线");
        after.creativeAssumptions=new ArrayList<>();
        after.creativeAssumptions.add("新的非权威创作假设");

        var scope=analyzer.analyze(before,after);

        assertThat(scope.continuityRequired()).isFalse();
        assertThat(scope.plotRequired()).isFalse();
        assertThat(scope.changedSections()).containsExactly("创作假设");
    }
}
