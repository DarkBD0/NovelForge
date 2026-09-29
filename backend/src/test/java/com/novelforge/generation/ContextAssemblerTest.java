package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.canon.CanonService;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.novel.WordCounter;
import com.novelforge.workflow.WorkflowRules;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblerTest {
    @Test void keepsPunctuationInsideChineseQuotesInOneAcceptanceItem() {
        assertThat(ContextAssembler.acceptanceItems("邱天以‘人不为己，天诛地灭’自我麻痹；姚远自杀。"))
                .containsExactly("邱天以‘人不为己，天诛地灭’自我麻痹","姚远自杀");
    }

    @Test void keepsCommaAndColonDetailsInsideOneEventUnit() {
        assertThat(ContextAssembler.acceptanceItems("现场推进：林砚查看门锁、短信和手表，男子随后离开；陆晓棠取得新证据。"))
                .containsExactly("现场推进：林砚查看门锁、短信和手表，男子随后离开","陆晓棠取得新证据");
    }

    @Test void labelsOccurredHistoryFutureDesignAndCurrentCandidateSeparately() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,1_000_000);
        Novel novel=new Novel(); novel.title="状态模型"; novel.synopsis="验证规划状态"; novel.targetWords=10000; novel.approvedMaxWords=11000;
        Artifact chapter=chapter(1,"第一章已经发生"); novel.artifacts.add(chapter);
        Artifact plan=plan(); novel.artifacts.add(plan); plan.needsRevision=true;

        var context=assembler.assemble(novel,Action.REWRITE,plan.id);
        var json=mapper.readTree(context.json());

        assertThat(json.path("stateModel").path("confirmedHistory").path("state").asText()).isEqualTo("CONFIRMED_OCCURRED");
        assertThat(json.path("stateModel").path("futurePlans").path("state").asText()).isEqualTo("CONFIRMED_CONDITIONAL_DESIGN");
        assertThat(json.path("planningState").path("mode").asText()).isEqualTo("HANDOFF_CALIBRATION");
        assertThat(json.path("revisionTarget").path("authorityState").asText()).isEqualTo("UNCONFIRMED_CANDIDATE");
        assertThat(json.path("revisionTarget").path("temporalState").asText()).isEqualTo("FUTURE_CONDITIONAL_DESIGN");
        assertThat(json.path("acceptedReferences").get(0).path("temporalState").asText()).isEqualTo("OCCURRED");
    }

    private Artifact chapter(int number,String summary) {
        Artifact artifact=new Artifact(); artifact.kind=Kind.CHAPTER; artifact.chapterNumber=number;
        Version version=new Version(); version.title="第一章"; version.content="已经发生的正文"; version.summary=summary;
        artifact.versions.add(version); artifact.approvedVersionId=version.id; return artifact;
    }

    private Artifact plan() {
        Plan plan=new Plan(); plan.startChapter=2; plan.endChapter=3; plan.prepareNextAfterChapter=2;
        plan.triggerReason="转折"; plan.handoff="承接"; plan.assumptions="未来假设";
        plan.chapters=List.of(new ChapterBeat(2,"追查","继续调查"),new ChapterBeat(3,"发现","发现真相"));
        Artifact artifact=new Artifact(); artifact.kind=Kind.PLAN; artifact.batchNumber=1;
        Version version=new Version(); version.title="规划"; version.content="未来规划"; version.summary="未来设计"; version.plan=plan;
        artifact.versions.add(version); artifact.approvedVersionId=version.id; return artifact;
    }
}
