package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.ChapterBeat;
import com.novelforge.novel.Novel.Review;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GenrePlanPolicyTest {
    private final GenrePlanPolicy policy=new GenrePlanPolicy(new GenreNarrativeProfile(new RequirementAuthority()));

    @Test void blocksThreeOpeningChaptersThatOnlyVerifyThePremise() {
        Novel novel=shuangNovel();
        Novel.Plan plan=plan(List.of(
                new ChapterBeat(1,"网上确认","查询秋招信息并确认异常"),
                new ChapterBeat(2,"线下确认","去就业办核对情况"),
                new ChapterBeat(3,"现场确认","到招聘会踩点验证没人报名")));

        Review result=policy.apply(novel,null,Action.PLAN,candidate(plan),passed());

        assertThat(result.passed()).isFalse();
        assertThat(result.issueDetails()).hasSize(1);
        assertThat(result.issueDetails()).allMatch(issue->"必须修正".equals(issue.severity()));
    }

    @Test void acceptsOpeningThatDeliversARealPayoffByChapterThree() {
        Novel novel=shuangNovel();
        Novel.Plan plan=plan(List.of(
                new ChapterBeat(1,"抢先投递","主角确认机会后立刻投递，成为唯一候选"),
                new ChapterBeat(2,"直接面试","企业跳过宣讲直接面试，主管当场改变态度"),
                new ChapterBeat(3,"首份录用","第一份offer到手，并引发其他企业争抢")));

        Review result=policy.apply(novel,null,Action.PLAN,candidate(plan),passed());

        assertThat(result.passed()).isTrue();
        assertThat(result.issueDetails()).isEmpty();
    }

    @Test void acceptsEarlySetbackWhenEachChapterMovesTowardPayoff() {
        Novel novel=shuangNovel();
        Novel.Plan plan=plan(List.of(
                new ChapterBeat(1,"面试受挫","主角被当众拒绝，发现可利用的独家机会"),
                new ChapterBeat(2,"主动争取","主角绕过无效争辩直接投递关键岗位，遇到更强竞争者"),
                new ChapterBeat(3,"反击准备","主角利用前世经验完成关键作品，逼对手提前应战")));

        assertThat(policy.apply(novel,null,Action.PLAN,candidate(plan),passed()).passed()).isTrue();
    }

    @Test void doesNotMistakeVerificationInsideActiveScenesForStalling() {
        Novel.Plan plan=plan(List.of(
                new ChapterBeat(1,"确认后受挫","确认招聘现场存在，却被当众拒绝"),
                new ChapterBeat(2,"核对后投递","核对岗位信息并主动投递，遭遇竞争者"),
                new ChapterBeat(3,"验证后争取","验证面试时间，直接争取临时面试")));

        assertThat(policy.apply(shuangNovel(),null,Action.PLAN,candidate(plan),passed()).passed()).isTrue();
    }

    @Test void doesNotApplyShuangCadenceToSuspenseNovel() {
        Novel novel=new Novel(); novel.title="雨夜失踪"; novel.requirements="悬疑推理";
        Novel.Plan plan=plan(List.of(new ChapterBeat(1,"确认","调查现场"),new ChapterBeat(2,"核对","核对证词"),new ChapterBeat(3,"验证","验证线索")));

        assertThat(policy.apply(novel,null,Action.PLAN,candidate(plan),passed()).passed()).isTrue();
    }

    @Test void rewriteChecksOnlyFutureBeatsWhenOpeningChaptersAlreadyOccurred() {
        Novel novel=shuangNovel();
        for(int number=1;number<=3;number++) {
            Novel.Artifact chapter=new Novel.Artifact(); chapter.kind=Novel.Kind.CHAPTER; chapter.chapterNumber=number;
            Novel.Version version=new Novel.Version(); version.title="第"+number+"章"; version.content="已发生"; version.summary="已发生";
            chapter.versions.add(version); chapter.approvedVersionId=version.id; novel.artifacts.add(chapter);
        }
        Novel.Artifact target=new Novel.Artifact(); target.kind=Novel.Kind.PLAN;
        Novel.Plan plan=plan(List.of(
                new ChapterBeat(1,"确认","查询异常"),new ChapterBeat(2,"核对","核对异常"),new ChapterBeat(3,"踩点","验证异常"),
                new ChapterBeat(4,"直接面试","成为唯一候选并直接面试"),new ChapterBeat(5,"首份录用","第一份offer到手"),new ChapterBeat(6,"企业争抢","多家企业开始抢人")));
        plan.endChapter=6;

        assertThat(policy.apply(novel,target,Action.REWRITE,candidate(plan),passed()).passed()).isTrue();
    }

    private Novel shuangNovel() {
        Novel novel=new Novel(); novel.title="重生秋招"; novel.requirements="纯粹的爽文，不要悬疑"; return novel;
    }
    private Novel.Plan plan(List<ChapterBeat> beats) {
        Novel.Plan plan=new Novel.Plan(); plan.startChapter=1; plan.endChapter=3; plan.chapters=beats; return plan;
    }
    private ModelGateway.Generated candidate(Novel.Plan plan) {
        return new ModelGateway.Generated("第一批规划","规划","摘要",List.of(),plan);
    }
    private Review passed() { return new Review(true,List.of(),false,false,false,List.of()); }
}
