package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.ChapterBeat;
import com.novelforge.novel.Novel.Review;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanBeatRepetitionPolicyTest {
    private final PlanBeatRepetitionPolicy policy=new PlanBeatRepetitionPolicy();

    @Test void shortStoryTwoRoundTripInvestigationsGetNonBlockingReaderSignal() {
        Novel novel=new Novel(); novel.targetWords=5000;
        Novel.Plan plan=plan(List.of(
                new ChapterBeat(1,"旧邻居","顾乔追问照片来历，对方回避，她决定核对照片细节",
                        1000,List.of(),"","顾乔决定从照片上的可辨认细节找起"),
                new ChapterBeat(2,"旧报","顾乔比对旧报和照片，确认合照拍摄地点",
                        1000,List.of(),"","顾乔带着新线索继续追查照片中的人"),
                beat(3,"公开","顾乔与邻居争执后公开关键证据，承担后果")));

        Review review=policy.apply(novel,null,Action.PLAN,candidate(plan),passed());

        assertThat(review.passed()).isTrue();
        assertThat(review.issueDetails()).singleElement().satisfies(issue->{
            assertThat(issue.severity()).isEqualTo("作者决定");
            assertThat(issue.evidence()).contains("第1章","第2章");
        });
    }

    @Test void quietButConsequentialRelationshipSceneIsNotTreatedAsResearchLoop() {
        Novel novel=new Novel(); novel.targetWords=5000;
        Novel.Plan plan=plan(List.of(
                new ChapterBeat(1,"旧邻居","顾乔追问照片来历，对方回避，她决定核对照片细节",
                        1000,List.of(),"","顾乔决定从照片上的可辨认细节找起"),
                new ChapterBeat(2,"母亲","顾乔查看母亲的旧信，与旧友和解，重新理解母亲当年的选择",
                        1000,List.of(),"","顾乔准备查清信中地址的来历"),
                beat(3,"公开","顾乔公开关键证据，承担后果")));

        assertThat(policy.apply(novel,null,Action.PLAN,candidate(plan),passed()).issueDetails()).isEmpty();
    }

    @Test void factualCertaintyAloneDoesNotHideASecondLookupChapter() {
        Novel novel=new Novel(); novel.targetWords=5000;
        Novel.Plan plan=plan(List.of(
                new ChapterBeat(1,"背面的名字","顾乔主动上门追问，并第一次面对邻居的回避",
                        1100,List.of(),"","顾乔决定绕开邻居，去查这栋楼本身的旧记录"),
                new ChapterBeat(2,"旧报上的火","独立旧报证实旧楼曾发生火灾，顾乔的判断动摇",
                        1100,List.of(),"","顾乔决定顺着火灾后的安置去向去找人"),
                beat(3,"登记簿","顾乔确认住户登记中的名字")));

        assertThat(policy.apply(novel,null,Action.PLAN,candidate(plan),passed()).issueDetails())
                .singleElement().satisfies(issue->assertThat(issue.severity()).isEqualTo("作者决定"));
    }

    @Test void flagsRepeatedFutureBeatsWithoutBlockingTheAuthor() {
        Novel novel=new Novel();
        Novel.Plan plan=plan(List.of(
                beat(5,"第一家","主角到第一家公司面试，因无人投递拿到意向，并要求写明非排他条款"),
                beat(6,"第二家","主角到第二家公司面试，因无人投递拿到意向，并要求写明非排他条款"),
                beat(7,"第三家","主角到第三家公司面试，因无人投递拿到意向，并要求写明非排他条款")));

        Review review=policy.apply(novel,null,Action.PLAN,candidate(plan),passed());

        assertThat(review.passed()).isTrue();
        assertThat(review.issueDetails()).singleElement().satisfies(issue->{
            assertThat(issue.severity()).isEqualTo("作者决定");
            assertThat(issue.evidence()).contains("第5章","第6章","第7章");
        });
    }

    @Test void changedChoiceAndConsequenceDoNotLookLikeTheSameBeat() {
        Novel.Plan plan=plan(List.of(
                beat(1,"投递","主角到公司面试，因无人投递拿到意向，并要求写明非排他条款"),
                beat(2,"泄露","公司泄露了主角的申请材料，主角放弃这份意向并追查是谁泄露"),
                beat(3,"取舍","主角拒绝原本最高薪的岗位，选择能保护自己隐私的团队")));

        assertThat(policy.apply(new Novel(),null,Action.PLAN,candidate(plan),passed()).issueDetails()).isEmpty();
    }

    @Test void differentVerificationWordingStillGetsReaderFacingAdvisory() {
        Novel.Plan plan=plan(List.of(
                beat(8,"频率","林砚核对钟声频率，确认录音和耳鸣指向同一频率"),
                beat(9,"接收端","林砚复核接收端记录，验证声音从自己的设备传出"),
                beat(10,"旧报道","林砚拒绝替旧同事隐瞒错报，承担公开纠正的代价"),
                beat(11,"责任","林砚与妹妹争执后决定讲出当年的事实"),
                beat(12,"记录者","林砚整理技术简报，确认自己是记录者")));

        Review result=policy.apply(new Novel(),null,Action.PLAN,candidate(plan),passed());

        assertThat(result.passed()).isTrue();
        assertThat(result.issueDetails()).singleElement().satisfies(issue->{
            assertThat(issue.severity()).isEqualTo("作者决定");
            assertThat(issue.evidence()).contains("第8章","第9章","第12章");
            assertThat(issue.suggestion()).contains("流程本身有戏剧作用时可以保留");
        });
    }

    @Test void meaningfulHumanChangePreventsProceduralAdvisory() {
        Novel.Plan plan=plan(List.of(
                beat(1,"证物","陈默核对原卡来源后决定不用它直接定罪，转而寻找独立证人"),
                beat(2,"对质","陈默与证人争执，失去对方信任却获得新的调查方向"),
                beat(3,"公开","陈默确认记录后拒绝提前曝光，选择先保护当事人")));

        assertThat(policy.apply(new Novel(),null,Action.PLAN,candidate(plan),passed()).issueDetails()).isEmpty();
    }

    @Test void confirmedChaptersAreNotFlaggedDuringPlanRewrite() {
        Novel novel=new Novel();
        for (int chapterNumber=1;chapterNumber<=3;chapterNumber++) {
            Novel.Artifact artifact=new Novel.Artifact(); artifact.kind=Novel.Kind.CHAPTER;
            artifact.chapterNumber=chapterNumber;
            Novel.Version version=new Novel.Version(); artifact.versions.add(version);
            artifact.approvedVersionId=version.id; novel.artifacts.add(artifact);
        }
        Novel.Artifact target=new Novel.Artifact(); target.kind=Novel.Kind.PLAN;
        Novel.Plan plan=plan(List.of(
                beat(1,"第一家","主角到第一家公司面试，因无人投递拿到意向，并要求写明非排他条款"),
                beat(2,"第二家","主角到第二家公司面试，因无人投递拿到意向，并要求写明非排他条款"),
                beat(3,"第三家","主角到第三家公司面试，因无人投递拿到意向，并要求写明非排他条款")));

        assertThat(policy.apply(novel,target,Action.REWRITE,candidate(plan),passed()).issueDetails()).isEmpty();
    }

    private ChapterBeat beat(int number,String title,String purpose) {
        return new ChapterBeat(number,title,purpose);
    }
    private Novel.Plan plan(List<ChapterBeat> beats) {
        Novel.Plan plan=new Novel.Plan(); plan.chapters=beats; return plan;
    }
    private ModelGateway.Generated candidate(Novel.Plan plan) {
        return new ModelGateway.Generated("规划","规划正文","摘要",List.of(),plan);
    }
    private Review passed() { return new Review(true,List.of(),false,false,false,List.of()); }
}
