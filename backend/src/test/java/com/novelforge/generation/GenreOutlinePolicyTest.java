package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Review;
import com.novelforge.outline.OutlineSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GenreOutlinePolicyTest {
    private final GenreOutlinePolicy policy=new GenreOutlinePolicy(
            new GenreNarrativeProfile(new RequirementAuthority()));

    @Test void blocksOldOutlineWhereOpeningOnlyVerifiesThePremise() {
        OutlineSpec spec=outline();
        spec.parts.getFirst().stages.getFirst().events.getFirst().event="主角先上网核对秋招人数";
        spec.parts.getFirst().stages.getFirst().events.getFirst().consequence="确认今年没人报名";

        Review result=policy.apply(shuangNovel(),null,Action.OUTLINE,"",candidate(spec),passed());

        assertThat(result.passed()).isFalse();
        assertThat(result.issueDetails()).hasSize(2);
        assertThat(result.issueDetails()).allMatch(issue->"必须修正".equals(issue.severity()));
    }

    @Test void acceptsConcreteOpeningPayoffAndStageEscalation() {
        OutlineSpec spec=outline();
        spec.openingPayoff="主角抢先投递，成为目标企业唯一候选人";
        spec.openingPayoffEventId="event-1";
        OutlineSpec.StoryStage stage=spec.parts.getFirst().stages.getFirst();
        stage.readerPayoff="看主角利用信息差抢占机会";
        stage.payoffResult="主角拿到优先面试机会，招聘主管主动联系";
        stage.escalation="从一家公司优先面试升级为多家企业竞争";
        stage.events.getFirst().event="主角确认信息差后当场投递目标企业";
        stage.events.getFirst().consequence="成为唯一候选人，招聘主管主动联系";

        Review result=policy.apply(shuangNovel(),null,Action.OUTLINE,"",candidate(spec),passed());

        assertThat(result.passed()).isTrue();
        assertThat(result.issueDetails()).isEmpty();
    }

    @Test void acceptsSetbackBeforeOpeningPayoff() {
        OutlineSpec spec=outline();
        OutlineSpec.StoryStage stage=spec.parts.getFirst().stages.getFirst();
        stage.title="受挫后反击";
        stage.readerPayoff="看主角从被拒绝走向抢占机会";
        stage.payoffResult="主角成为目标企业唯一候选人";
        stage.escalation="竞争由一家公司扩展到多家公司";
        stage.events.getFirst().event="主角在招聘会受挫，意识到应改变投递策略";
        stage.events.getFirst().consequence="主角决定利用前世信息差主动争取";
        OutlineSpec.KeyEvent second=new OutlineSpec.KeyEvent();
        second.id="event-2"; second.title="抢占机会";
        second.event="主角抢先投递目标企业";
        second.consequence="成为唯一候选人，获得面试邀请";
        stage.events.add(second);
        spec.openingPayoff="成为唯一候选人，获得面试邀请";
        spec.openingPayoffEventId="event-2";

        assertThat(policy.apply(shuangNovel(),null,Action.OUTLINE,"",candidate(spec),passed()).passed()).isTrue();
    }

    @Test void doesNotForcePayoffFieldsOnSuspenseOutline() {
        Novel novel=new Novel(); novel.title="雨夜失踪"; novel.requirements="悬疑推理";

        Review result=policy.apply(novel,null,Action.OUTLINE,"",candidate(outline()),passed());

        assertThat(result.passed()).isTrue();
    }

    @Test void explicitShuangRevisionIsCheckedEvenWhenOriginalNovelWasNotShuang() {
        Novel novel=new Novel(); novel.title="雨夜失踪"; novel.requirements="悬疑推理";

        Review result=policy.apply(novel,null,Action.OUTLINE,"改成爽文",candidate(outline()),passed());

        assertThat(result.passed()).isFalse();
    }

    @Test void explicitSwitchAwayFromShuangDoesNotKeepOldGenreGate() {
        Review result=policy.apply(shuangNovel(),null,Action.OUTLINE,
                "这次改成悬疑，不要爽文",candidate(outline()),passed());

        assertThat(result.passed()).isTrue();
    }

    private Novel shuangNovel() {
        Novel novel=new Novel(); novel.title="重生秋招"; novel.requirements="纯粹的爽文"; return novel;
    }

    private OutlineSpec outline() {
        OutlineSpec spec=new OutlineSpec(); spec.genreTone="都市职场";
        OutlineSpec.StoryPart part=new OutlineSpec.StoryPart(); part.id="part-1"; part.title="抢占先机";
        OutlineSpec.StoryStage stage=new OutlineSpec.StoryStage(); stage.id="stage-1"; stage.title="确认信息差";
        OutlineSpec.KeyEvent event=new OutlineSpec.KeyEvent(); event.id="event-1"; event.title="确认异常";
        stage.events.add(event); part.stages.add(stage); spec.parts.add(part);
        return spec;
    }

    private ModelGateway.Generated candidate(OutlineSpec spec) {
        return new ModelGateway.Generated("大纲","大纲正文","摘要",List.of(),null,spec,List.of());
    }

    private Review passed() { return new Review(true,List.of(),false,false,false,List.of()); }
}
