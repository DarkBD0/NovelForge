package com.novelforge;

import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.generation.PlanNarrativeRenderer;
import com.novelforge.generation.DeterministicStyleEditor;
import com.novelforge.generation.ModelGateway;
import com.novelforge.generation.ContextAssembler;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.outline.OutlineRenderer;
import com.novelforge.outline.OutlineStructureService;
import com.novelforge.task.TaskService;
import com.novelforge.workflow.WorkflowRules;
import com.novelforge.workflow.WorkflowService;
import com.novelforge.novel.WordCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Explicit local acceptance only: run with -Dtest=LivePlanAcceptanceIT and a model env file. */
@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:live-plan-acceptance;DB_CLOSE_DELAY=-1",
        "novelforge.storage.mode=legacy",
        "novelforge.model.mode=http",
        "novelforge.es.enabled=false",
        "novelforge.neo4j.enabled=false",
        "novelforge.experiments.continuity-shadow-enabled=false",
        "novelforge.experiments.plot-foreshadow-shadow-enabled=false"
})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class LivePlanAcceptanceIT {
    @Autowired NovelRepository repository;
    @Autowired WorkflowService workflow;
    @Autowired WorkflowRules rules;
    @Autowired WordCounter words;
    @Autowired TaskService tasks;
    @Autowired OutlineRenderer outlineRenderer;
    @Autowired OutlineStructureService outlineStructure;
    @Autowired DeterministicStyleEditor styleEditor;
    @Autowired ModelGateway modelGateway;

    @Test void realModelCreatesExplicitEndingGoalsForANewShortOutline() {
        Novel novel=new Novel(); novel.title="雨夜的回信";
        novel.synopsis="顾乔收到失踪姐姐寄来的旧明信片。她沿着明信片上的线索寻找，最终确认姐姐是否愿意见她。";
        novel.targetWords=3000; novel.approvedMaxWords=3300;
        String context="""
                {"title":"雨夜的回信","synopsis":"顾乔收到失踪姐姐寄来的旧明信片。她沿着明信片上的线索寻找，最终确认姐姐是否愿意见她。",
                "requirements":"短篇悬疑，人物选择比复杂机关重要","targetWords":3000,"approvedMaxWords":3300,
                "genreContract":{"mode":"SUSPENSE"}}
                """;

        ModelGateway.Generated generated=modelGateway.generate(new ModelGateway.Request(Action.OUTLINE,novel,null,
                new ContextAssembler.Context(context,List.of(),1,1),""));

        assertThat(generated.outlineSpec()).isNotNull();
        assertThat(generated.outlineSpec().schemaVersion).isEqualTo("4");
        assertThat(generated.outlineSpec().endingGoals).hasSizeBetween(1,3);
        assertThat(generated.outlineSpec().endingGoals).allSatisfy(goal->
                assertThat(goal.proofStandard).isNotBlank());
    }

    @Test void realModelDoesNotTreatAWeakRumorAsThePlannedEndingResult() {
        Novel novel=new Novel(); novel.title="失踪的姐姐";
        Artifact target=new Artifact(); target.kind=Kind.CHAPTER; target.chapterNumber=5;
        String requirement="结局目标“查清姐姐的具体去向”：联系本人；依据来源：本人回应；必须得到：她所在的城市；不得越过：不能推断详细住址";
        String prose="老邻居说姐姐可能去了南方，具体哪座城市她也不清楚。顾乔没有联系上姐姐，只好先回家。";
        var candidate=new ModelGateway.Generated("第五章",prose,"顾乔只得到一个模糊去向",List.of(),null);
        var context=new ContextAssembler.Context("{\"currentChapterPlan\":{\"acceptanceItems\":[\""+requirement+"\"]}}",
                List.of(),5,1);
        Review review=modelGateway.review(new ModelGateway.Request(Action.REVIEW,novel,target,context,""),candidate);

        assertThat(review.passed()).isFalse();
        assertThat(review.issueDetails()).anyMatch(issue->issue.problem().contains("查清姐姐的具体去向"));
    }

    @Test void realModelAcceptsAVisibleCareerChoiceWithoutDemandingInvestigationEvidence() {
        Novel novel=new Novel(); novel.title="最后一份录用";
        Artifact target=new Artifact(); target.kind=Kind.CHAPTER; target.chapterNumber=5;
        String requirement="结局目标“选定工作”：主人公亲自作出选择；依据来源：回复录用邮件并得到确认；必须得到：他接受甲公司的岗位；不得越过：不声称其他公司已经签约";
        String prose="他把乙公司的录用信放进抽屉，回复甲公司的人事：我接受这个岗位。十分钟后，甲公司回信确认他的入职日期。他把这封回信给母亲看，终于能说自己要去哪里上班。";
        var candidate=new ModelGateway.Generated("第五章",prose,"主人公接受甲公司的岗位并收到确认",List.of(),null);
        var context=new ContextAssembler.Context("{\"currentChapterPlan\":{\"acceptanceItems\":[\""+requirement+"\"]}}",
                List.of(),5,1);
        Review review=modelGateway.review(new ModelGateway.Request(Action.REVIEW,novel,target,context,""),candidate);

        assertThat(review.issueDetails()).noneMatch(issue->issue.problem().contains("正文没有完整落实规划要求"));
    }

    @Test void realCompletionDoesNotCountAFuturePlanAsConfirmedEndingProse() {
        Novel novel=new Novel(); novel.title="失踪的姐姐"; novel.synopsis="顾乔寻找失踪的姐姐，最终查清她的具体去向";
        String context="""
                {"title":"失踪的姐姐","synopsis":"顾乔寻找失踪的姐姐，最终查清她的具体去向",
                "completionGoals":[{"id":"where","goal":"查清姐姐的具体去向","proofStandard":"已确认正文中有可信信息确定她所在城市","sourceVersionId":"outline_1"}],
                "acceptedReferences":[{"kind":"PLAN","authorityState":"CONFIRMED","temporalState":"FUTURE_CONDITIONAL_DESIGN","summary":"第五章将联系姐姐，查明她住在哪座城市"},
                {"kind":"CHAPTER","authorityState":"CONFIRMED","temporalState":"OCCURRED","chapter":5,"summary":"顾乔只听邻居说姐姐可能去了南边，具体城市未知","content":"邻居说姐姐可能去了南边，具体哪座城市她也不清楚。顾乔没有联系上姐姐。"}]}
                """;

        Review review=modelGateway.review(new ModelGateway.Request(Action.COMPLETE,novel,null,
                new ContextAssembler.Context(context,List.of(),6,1),""),null);

        assertThat(review.mainlineResolved() && review.endingClear()).isFalse();
        assertThat(review.issueDetails()).anyMatch(issue->issue.problem().contains("去向")
                || issue.problem().contains("城市"));
    }

    @Test void realModelFlagsUnsupportedSourceAndUndatedPhotoInference() {
        Novel novel=new Novel(); novel.title="证据边界检查验收";
        Artifact target=new Artifact(); target.kind=Kind.CHAPTER; target.chapterNumber=2;
        String prose="""
                旧报上只写了城西梧桐街曾有火灾，没有楼号，也没有楼名。
                顾乔放下报纸，确定自己住的梧桐街十七号这栋楼当年失过火。
                照片上没有日期，她却看着报纸日期与照片的拍摄年份，算出两者相差五年。
                她于是决定依据这个年份差去查林秋宁的迁出记录。
                """;
        ModelGateway.Generated candidate=new ModelGateway.Generated("第二章",prose,"顾乔查阅旧报并确定旧楼火灾和照片年份差",List.of(),null);
        var request=new ModelGateway.Request(Action.CHAPTER,novel,target,
                new ContextAssembler.Context("{\"currentChapterPlan\":{\"purpose\":\"寻找旧楼火灾的可靠来源\",\"acceptanceItems\":[]}}",
                        List.of(),2,1),"");

        Review review=modelGateway.review(request,candidate);

        System.out.println("LIVE_EVIDENCE_REVIEW_PASSED="+review.passed());
        review.issueDetails().forEach(issue->System.out.println("LIVE_EVIDENCE_ISSUE="+issue.severity()+" "+issue.problem()));
        assertThat(review.passed()).isFalse();
        assertThat(review.issueDetails()).anyMatch(issue->"必须修正".equals(issue.severity()));
    }

    @Test void realModelFlagsContradictoryNameCarrierInOneChapter() {
        Novel novel=new Novel(); novel.title="旧照线索核验";
        Artifact target=new Artifact(); target.kind=Kind.CHAPTER; target.chapterNumber=1;
        String prose="""
                顾乔从母亲的衣柜里找出一张旧照片和一封没有寄出的短笺。
                顾乔把短笺翻过来。背面用圆珠笔写着一个名字：林秋宁。
                她盯着照片里母亲身边的女人看，想不起母亲提过这个人。
                她又看了看照片背面的“林秋宁”三个字，决定去问旧邻居。
                """;
        var candidate=new ModelGateway.Generated("第一章",prose,"顾乔发现林秋宁的名字",List.of(),null);
        var request=new ModelGateway.Request(Action.REVIEW,novel,target,
                new ContextAssembler.Context("{\"previousChapterSummary\":\"\",\"currentChapterPlan\":{\"acceptanceItems\":[]}}",
                        List.of(),1,1),"");

        Review review=modelGateway.review(request,candidate);

        review.issueDetails().forEach(issue->System.out.println("LIVE_CARRIER_ISSUE="+issue.severity()+" "+issue.problem()));
        assertThat(review.passed()).isFalse();
        assertThat(review.issueDetails()).anyMatch(issue->"必须修正".equals(issue.severity())
                && issue.problem().contains("短笺") && issue.problem().contains("照片")
                && issue.evidence().contains("背面用圆珠笔写着一个名字：林秋宁"));
    }

    @Test void realModelFlagsPlanGoalWithoutVerifiableClue() {
        Novel novel=new Novel(); novel.title="旧照片线索验收";
        Plan plan=new Plan(); plan.startChapter=4; plan.endChapter=5; plan.finalBatch=true;
        plan.chapters=List.of(
                new ChapterBeat(4,"模糊去向","顾乔获得可独立核实的具体去向",850,
                        List.of("邻居只记得林秋宁投奔南边亲戚，具体城市她不确定，也没有姓名地址或其他记录"),"",""),
                new ChapterBeat(5,"直接寻人","顾乔只凭邻居提供的地点直接找到林秋宁的住址",850,
                        List.of("顾乔没有其他线索，直接按照邻居说的具体地址登门"),"",""));
        var candidate=new ModelGateway.Generated("章节规划",PlanNarrativeRenderer.render(plan),"顾乔寻找林秋宁",List.of(),plan);
        var request=new ModelGateway.Request(Action.PLAN,novel,null,
                new ContextAssembler.Context("{\"nextChapter\":4}",List.of(),4,1),"");

        Review review=modelGateway.review(request,candidate);

        review.issueDetails().forEach(issue->System.out.println("LIVE_PLAN_CLUE_ISSUE="+issue.severity()+" "+issue.problem()));
        assertThat(review.passed()).isFalse();
        assertThat(review.issueDetails()).anyMatch(issue->"必须修正".equals(issue.severity()));
    }

    @Test void realModelDoesNotTreatVagueHearsayAsVerifiedDestination() {
        Novel novel=new Novel(); novel.title="旧照调查";
        String context="""
                {"title":"旧照调查","synopsis":"顾乔要查清林秋宁的具体去向与母亲未寄信的缘由。",
                 "authoritativeReferences":[{"kind":"PLAN","content":"最终章必须独立核实林秋宁的具体去向，查明母亲未寄信的缘由。"}],
                 "confirmedChapterSummaries":[{"chapter":5,"summary":"顾乔据周惠芳转述，林秋宁可能搬去了外省；她猜母亲怕打扰对方所以没寄信。"}],
                 "recentConfirmedChapterProse":[{"chapter":5,"content":"周惠芳说：‘听厂里老姐妹提过一嘴，林秋宁后来去了外省，具体城市我也不知道。’顾乔没有再查登记或见林秋宁本人。她想，母亲也许怕打扰对方，才没有寄出那封信。"}]}
                """;
        var request=new ModelGateway.Request(Action.COMPLETE,novel,null,
                new ContextAssembler.Context(context,List.of(),6,1),"");

        Review review=modelGateway.review(request,null);

        review.issueDetails().forEach(issue->System.out.println("LIVE_COMPLETION_DESTINATION_ISSUE="+issue.severity()+" "+issue.problem()));
        assertThat(review.mainlineResolved()).isFalse();
    }

    @Test void realModelFlagsFinalPlanThatPostponesOutlineEnding() {
        Novel novel=new Novel(); novel.title="旧照调查";
        Plan plan=new Plan(); plan.startChapter=5; plan.endChapter=5; plan.finalBatch=true;
        plan.chapters=List.of(new ChapterBeat(5,"邻里和解","顾乔决定以后再找林秋宁，先向旧邻居道歉",900,
                List.of("顾乔向旧邻居道歉，旧邻居接受；林秋宁的具体去向仍然不明，顾乔打算以后再查"),
                "不揭示林秋宁的住处","顾乔打算以后继续调查"));
        var candidate=new ModelGateway.Generated("最终规划",PlanNarrativeRenderer.render(plan),"顾乔与邻居和解",List.of(),plan);
        var request=new ModelGateway.Request(Action.PLAN,novel,null,
                new ContextAssembler.Context("{\"planStoryEngine\":{\"endingContract\":\"顾乔查清林秋宁的具体去向并理解母亲未寄信的缘由\"}}",
                        List.of(),5,1),"");

        Review review=modelGateway.review(request,candidate);

        review.issueDetails().forEach(issue->System.out.println("LIVE_FINAL_PLAN_ISSUE="+issue.severity()+" "+issue.problem()));
        assertThat(review.passed()).isFalse();
        assertThat(review.issueDetails()).anyMatch(issue->"必须修正".equals(issue.severity()));
    }

    @Test void realModelPlansAndReviewsAgainstIsolatedNovel() {
        boolean personalStake=Boolean.getBoolean("novelforge.acceptance.personalStake");
        boolean fullStory=Boolean.getBoolean("novelforge.acceptance.fullStory");
        String synopsis=personalStake
                ?"顾乔整理亡母遗物，发现一张没有日期的旧合照和一封母亲没有寄出的短笺。照片背面写着“林秋宁”，旧住户名单却找不到她。"
                    +"顾乔想弄清母亲为何留下这些东西；旧邻居被问到此人时回避，但顾乔尚不知道旧楼曾发生火灾。"
                :"顾乔收到一张没有日期的旧合照，背面写着合照中一人的名字“林秋宁”。旧住户名单上找不到这个名字。"
                    +"旧邻居被问到此人时避开目光，但顾乔尚不知道这栋楼发生过火灾。她要查明失踪者的去向。";
        System.out.println("LIVE_PREMISE_PERSONAL="+personalStake);
        Novel novel=workflow.create("雨夜旧照验收",synopsis,5000,
                "悬疑故事。证据只能支持其产生时点；人物不能提前得知火灾，不把邻居回避直接写成有人刻意抹除。最终说明失踪者去向。") ;
        novel=repository.update(novel.id,current->{
            current.autoStyleEnabled=false;
            var spec=OutlineTestFixtures.validSpec(5000,
                    (personalStake?"顾乔从亡母遗物中找到旧照与未寄出的短笺，想弄清母亲与林秋宁的关系；":"顾乔循一张无日期旧合照寻找背面署名的林秋宁，")
                    +"现存旧住户名单没有这个名字。她起初不知旧楼曾有火灾；问照片时邻居避开目光，后来她才从独立资料得知火灾。"
                    +"调查必须区分照片所示、人物猜测和可核对事实，最后查清失踪者去向。");
            spec.genreTone="悬疑、克制、人物有限视角";
            spec.characters.getFirst().name="顾乔";
            spec.characters.getFirst().goal=personalStake?"弄清林秋宁与母亲的关系及其去向":"查明合照中失踪者的去向";
            if(personalStake) {
                spec.characters.getFirst().motivation="母亲已去世，她不愿再错过母亲没说出口的事";
                spec.characters.getFirst().keyChoice="不再靠猜测责怪旧邻居，坦诚询问母亲留下的未解之事";
                spec.readerAttraction="母亲为何留下照片与没寄出的短笺，林秋宁去了哪里";
                spec.coreConflict="顾乔追问母亲的往事时，可能失去与仍在身边的亲友之间的信任";
                spec.endingContract="查明林秋宁的具体去向与母亲未寄信的缘由；顾乔修复或明确承受追问造成的人际后果";
                var endingEvent=spec.parts.getFirst().stages.getFirst().events.getFirst();
                endingEvent.event="顾乔通过可信证言或可核对资料得知林秋宁的具体去向，并理解母亲为何没有寄出短笺";
                endingEvent.consequence="私人寻找有明确答案，顾乔与旧邻居的关系有可见结果";
                endingEvent.irreversibleTurn="顾乔坦诚面对自己因猜测伤害了旧邻居";
            }
            spec.worldRules.getFirst().rule="邻居回避只能证明不愿回答，不能单凭此认定有人刻意隐瞒";
            Artifact outline=new Artifact(); outline.kind=Kind.OUTLINE;
            Version outlineVersion=new Version(); outlineVersion.title="全书大纲";
            outlineVersion.content=outlineRenderer.render(spec);
            outlineVersion.summary="顾乔由旧照追查失踪者，先怀疑，后以独立证据查明真相";
            outlineVersion.outlineSpec=spec; outlineVersion.outlineSpecHash=outlineStructure.hash(spec);
            outline.versions.add(outlineVersion); outline.approvedVersionId=outlineVersion.id;
            Artifact characters=new Artifact(); characters.kind=Kind.CHARACTERS;
            Version setting=new Version(); setting.title="人物设定";
            setting.content=personalStake
                    ?"顾乔：母亲刚去世，整理遗物时发现旧照与未寄出的短笺；想了解母亲隐而未言的往事，起初不知道旧楼火灾。"
                        +"旧邻居：见到照片时回避问题，原因待调查。林秋宁：旧照背面的署名者，去向未明。"
                    :"顾乔：寻找失踪者的当事人，开始调查时不知道旧楼火灾。旧邻居：见到照片时回避问题，其真实原因待调查。";
            setting.summary="顾乔尚不知火灾；旧邻居回避原因未证实";
            characters.versions.add(setting); characters.approvedVersionId=setting.id;
            current.artifacts.addAll(List.of(outline,characters)); return current;
        });

        Task submitted=tasks.submit(novel.id,Action.PLAN,null,"按证据边界安排接下来的章节，不要过早确认人为隐瞒。",
                Novel.uid(),novel.revision);
        String novelId=novel.id;
        await().atMost(Duration.ofMinutes(7)).until(()->{
            TaskStatus status=tasks.find(repository.get(novelId),submitted.id).status;
            return status!=TaskStatus.QUEUED && status!=TaskStatus.RUNNING;
        });

        Novel after=repository.get(novelId);
        Task task=tasks.find(after,submitted.id);
        System.out.println("LIVE_PLAN_TASK="+task.status+" "+task.error);
        after.artifacts.stream().filter(a->a.kind==Kind.PLAN).findFirst().ifPresent(saved->{
            Version candidate=saved.latest();
            if(candidate!=null) System.out.println("LIVE_PLAN_CANDIDATE="+candidate.content.replace("\n","\\n"));
        });
        assertThat(task.status).as(task.error).isEqualTo(TaskStatus.SUCCEEDED);
        Artifact plan=after.artifacts.stream().filter(a->a.kind==Kind.PLAN).findFirst().orElseThrow();
        assertThat(plan.latest().plan).isNotNull();
        assertThat(plan.latest().review).isNotNull();
        System.out.println("LIVE_PLAN_CHAPTERS="+plan.latest().plan.chapters.size());
        System.out.println("LIVE_PLAN_PASSED="+plan.latest().review.passed());
        System.out.println("LIVE_PLAN_ISSUES="+plan.latest().review.issueDetails().size());
        plan.latest().plan.chapters.forEach(chapter->System.out.println(
                "LIVE_PLAN_BEAT="+chapter.number()+" "+chapter.purpose()+" | 场景="+chapter.sceneBeats()
                        +" | 揭示边界="+chapter.revealBoundary()+" | 章末="+chapter.endingHook()));
        plan.latest().review.issueDetails().forEach(issue->System.out.println(
                "LIVE_PLAN_ISSUE="+issue.severity()+" "+issue.problem()+" | 依据="+issue.evidence()
                        +" | 建议="+issue.suggestion()));

        String planArtifactId=plan.id;
        int planFixAttempt=0;
        while(fullStory && !plan.latest().review.passed() && plan.latest().draftIssues.isEmpty()
                && planFixAttempt<2) {
            String guidance=plan.latest().review.issueDetails().stream()
                    .filter(issue->"必须修正".equals(issue.severity()))
                    .map(ReviewIssue::text).reduce((left,right)->left+"\n"+right).orElse("");
            if(guidance.isBlank()) break;
            Task repair=tasks.submit(novelId,Action.REWRITE,planArtifactId,
                    "只修正当前章节规划中的硬性因果或预算问题，保留已成立的章节作用；不得改已确认大纲和正文。\n"+guidance,
                    Novel.uid(),after.revision);
            String repairTaskId=repair.id;
            await().atMost(Duration.ofMinutes(7)).until(()->{
                TaskStatus status=tasks.find(repository.get(novelId),repairTaskId).status;
                return status!=TaskStatus.QUEUED && status!=TaskStatus.RUNNING;
            });
            after=repository.get(novelId);
            Task repaired=tasks.find(after,repairTaskId);
            plan=after.artifacts.stream().filter(a->a.id.equals(planArtifactId)).findFirst().orElseThrow();
            planFixAttempt++;
            System.out.println("LIVE_PLAN_FIX_TASK="+planFixAttempt+" "+repaired.status+" "+repaired.error);
            if(plan.latest().review!=null) {
                System.out.println("LIVE_PLAN_FIX_REVIEW="+planFixAttempt+" "+plan.latest().review.passed());
                int round=planFixAttempt;
                plan.latest().review.issueDetails().forEach(issue->System.out.println(
                        "LIVE_PLAN_FIX_ISSUE="+round+" "+issue.severity()+" "+issue.problem()));
            }
            if(repaired.status!=TaskStatus.SUCCEEDED || plan.latest().review==null) break;
        }

        if (!plan.latest().review.passed() || !plan.latest().draftIssues.isEmpty()) {
            System.out.println("LIVE_CHAIN_STOP=规划尚不能确认");
            if(fullStory) assertThat(plan.latest().review.passed()).as("完整验收需要可确认的规划").isTrue();
            return;
        }
        Novel current=workflow.confirm(novelId,plan.id,plan.latest().id,after.revision,null);
        int plannedEnd=plan.latest().plan.endChapter;
        int stopAt=fullStory?10:Math.min(2,plannedEnd);
        if(fullStory && plannedEnd>10) {
            System.out.println("LIVE_CHAIN_STOP=超过本次十章验收调用预算，需另行评估");
            assertThat(plannedEnd).as("本次验收调用预算").isLessThanOrEqualTo(10);
            return;
        }
        int confirmedChapters=0;
        for (int chapterNumber=1;chapterNumber<=stopAt;chapterNumber++) {
            if(fullStory && "PLAN".equals(rules.nextAction(current))) {
                Task nextPlanTask=tasks.submit(novelId,Action.PLAN,null,
                        "承接已确认正文和未写完的前批规划，提前设计下一批。",Novel.uid(),current.revision);
                String nextPlanTaskId=nextPlanTask.id;
                await().atMost(Duration.ofMinutes(7)).until(()->{
                    TaskStatus status=tasks.find(repository.get(novelId),nextPlanTaskId).status;
                    return status!=TaskStatus.QUEUED && status!=TaskStatus.RUNNING;
                });
                current=repository.get(novelId);
                Task nextPlanFinished=tasks.find(current,nextPlanTaskId);
                System.out.println("LIVE_CHAIN_NEXT_PLAN_TASK="+nextPlanFinished.status+" "+nextPlanFinished.error);
                Artifact nextPlan=current.artifacts.stream().filter(a->a.kind==Kind.PLAN && !a.clean())
                        .reduce((first,second)->second).orElse(null);
                if(nextPlan==null || nextPlanFinished.status!=TaskStatus.SUCCEEDED) break;
                Version nextVersion=nextPlan.latest();
                System.out.println("LIVE_CHAIN_NEXT_PLAN_REVIEW="+(nextVersion.review!=null && nextVersion.review.passed())
                        +" "+nextVersion.draftIssues);
                if(nextVersion.plan!=null) nextVersion.plan.chapters.forEach(beat->System.out.println(
                        "LIVE_CHAIN_NEXT_PLAN_BEAT="+beat.number()+" "+beat.purpose()+" | 场景="+beat.sceneBeats()
                                +" | 揭示边界="+beat.revealBoundary()+" | 章末="+beat.endingHook()));
                if(nextVersion.review!=null) nextVersion.review.issueDetails().forEach(issue->System.out.println(
                        "LIVE_CHAIN_NEXT_PLAN_ISSUE="+issue.severity()+" "+issue.problem()));
                if(nextVersion.review==null || !nextVersion.review.passed() || !nextVersion.draftIssues.isEmpty()) break;
                current=workflow.confirm(novelId,nextPlan.id,nextVersion.id,current.revision,null);
                plannedEnd=nextVersion.plan.endChapter;
            }
            if(fullStory && "COMPLETE".equals(rules.nextAction(current))) break;
            if(!"CHAPTER".equals(rules.nextAction(current))) break;
            Task chapterTask=tasks.submit(novelId,Action.CHAPTER,null,"",Novel.uid(),current.revision);
            String taskId=chapterTask.id;
            await().atMost(Duration.ofMinutes(7)).until(()->{
                TaskStatus status=tasks.find(repository.get(novelId),taskId).status;
                return status!=TaskStatus.QUEUED && status!=TaskStatus.RUNNING;
            });
            current=repository.get(novelId);
            Task finished=tasks.find(current,taskId);
            System.out.println("LIVE_CHAIN_CHAPTER_TASK="+chapterNumber+" "+finished.status+" "+finished.error);
            int expected=chapterNumber;
            Artifact chapter=current.artifacts.stream().filter(a->a.kind==Kind.CHAPTER && a.chapterNumber==expected)
                    .findFirst().orElse(null);
            if (chapter==null) break;
            Version version=chapter.latest();
            System.out.println("LIVE_CHAIN_CHAPTER_REVIEW="+chapterNumber+" "
                    +(version.review!=null && version.review.passed())+" "+version.draftIssues);
            if (version.review!=null) version.review.issueDetails().forEach(issue->System.out.println(
                    "LIVE_CHAIN_CHAPTER_ISSUE="+expected+" "+issue.severity()+" "+issue.problem()));
            System.out.println("LIVE_CHAIN_CHAPTER_PROSE="+chapterNumber+" "+version.content.replace("\n","\\n"));
            if(fullStory && finished.status==TaskStatus.FAILED && version.review!=null
                    && version.review.passed() && version.stateExtractionStatus==StateExtractionStatus.FAILED) {
                String originalVersionId=version.id,originalProse=version.content;
                Task retry=tasks.submitStateExtraction(novelId,chapter.id,Novel.uid(),current.revision);
                String retryTaskId=retry.id;
                await().atMost(Duration.ofMinutes(7)).until(()->{
                    TaskStatus status=tasks.find(repository.get(novelId),retryTaskId).status;
                    return status!=TaskStatus.QUEUED && status!=TaskStatus.RUNNING;
                });
                current=repository.get(novelId);
                finished=tasks.find(current,retryTaskId);
                version=current.artifacts.stream().filter(a->a.id.equals(chapter.id)).findFirst().orElseThrow().latest();
                System.out.println("LIVE_CHAIN_STATE_RETRY="+chapterNumber+" "+finished.status+" "+finished.error);
                assertThat(version.id).isEqualTo(originalVersionId);
                assertThat(version.content).isEqualTo(originalProse);
            }
            int fixAttempt=0;
            while(fullStory && finished.status==TaskStatus.SUCCEEDED && version.review!=null
                    && !version.review.passed() && version.draftIssues.isEmpty() && fixAttempt<2) {
                Task fix=tasks.submitContentFix(novelId,chapter.id,Novel.uid(),current.revision);
                String fixTaskId=fix.id;
                await().atMost(Duration.ofMinutes(7)).until(()->{
                    TaskStatus status=tasks.find(repository.get(novelId),fixTaskId).status;
                    return status!=TaskStatus.QUEUED && status!=TaskStatus.RUNNING;
                });
                current=repository.get(novelId);
                finished=tasks.find(current,fixTaskId);
                version=current.artifacts.stream().filter(a->a.id.equals(chapter.id)).findFirst().orElseThrow().latest();
                fixAttempt++;
                System.out.println("LIVE_CHAIN_FIX_TASK="+chapterNumber+" round="+fixAttempt+" "
                        +finished.status+" "+finished.error);
                System.out.println("LIVE_CHAIN_FIX_REVIEW="+chapterNumber+" round="+fixAttempt+" "
                        +(version.review!=null && version.review.passed())+" "+version.draftIssues);
                int completedRound=fixAttempt;
                if(version.review!=null) version.review.issueDetails().forEach(issue->System.out.println(
                        "LIVE_CHAIN_FIX_ISSUE="+expected+" round="+completedRound+" "+issue.severity()+" "+issue.problem()));
                System.out.println("LIVE_CHAIN_FIX_PROSE="+chapterNumber+" round="+fixAttempt+" "
                        +version.content.replace("\n","\\n"));
            }
            if(fullStory && finished.status==TaskStatus.FAILED && version.review!=null
                    && version.review.passed() && version.stateExtractionStatus==StateExtractionStatus.FAILED) {
                String savedVersionId=version.id,savedProse=version.content;
                Task retry=tasks.submitStateExtraction(novelId,chapter.id,Novel.uid(),current.revision);
                String retryTaskId=retry.id;
                await().atMost(Duration.ofMinutes(7)).until(()->{
                    TaskStatus status=tasks.find(repository.get(novelId),retryTaskId).status;
                    return status!=TaskStatus.QUEUED && status!=TaskStatus.RUNNING;
                });
                current=repository.get(novelId);
                finished=tasks.find(current,retryTaskId);
                version=current.artifacts.stream().filter(a->a.id.equals(chapter.id)).findFirst().orElseThrow().latest();
                System.out.println("LIVE_CHAIN_POST_FIX_STATE_RETRY="+chapterNumber+" "+finished.status+" "+finished.error);
                assertThat(version.id).isEqualTo(savedVersionId);
                assertThat(version.content).isEqualTo(savedProse);
            }
            if (finished.status!=TaskStatus.SUCCEEDED || version.review==null || !version.review.passed()
                    || !version.draftIssues.isEmpty()) break;
            current=workflow.confirm(novelId,chapter.id,version.id,current.revision,null);
            confirmedChapters++;
        }
        if(!fullStory) return;
        System.out.println("LIVE_CHAIN_CONFIRMED="+confirmedChapters+"/"+plannedEnd);
        System.out.println("LIVE_CHAIN_NEXT_ACTION="+rules.nextAction(current));
        System.out.println("LIVE_CHAIN_WORDS="+words.approvedWords(current));
        if(!"COMPLETE".equals(rules.nextAction(current))) {
            System.out.println("LIVE_CHAIN_STOP=十章验收预算内未进入完结检查，或存在待修订内容");
            assertThat(rules.nextAction(current)).as("本次验收需进入完结检查").isEqualTo("COMPLETE");
            return;
        }
        Task completionTask=tasks.submit(novelId,Action.COMPLETE,null,"",Novel.uid(),current.revision);
        String completionTaskId=completionTask.id;
        await().atMost(Duration.ofMinutes(7)).until(()->{
            TaskStatus status=tasks.find(repository.get(novelId),completionTaskId).status;
            return status!=TaskStatus.QUEUED && status!=TaskStatus.RUNNING;
        });
        current=repository.get(novelId);
        Task completionFinished=tasks.find(current,completionTaskId);
        System.out.println("LIVE_COMPLETION_TASK="+completionFinished.status+" "+completionFinished.error);
        assertThat(completionFinished.status).as(completionFinished.error).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(current.completionChecks).isNotEmpty();
        Completion check=current.completionChecks.getLast();
        System.out.println("LIVE_COMPLETION_REVIEW="+check.review().passed()+" mainline="
                +check.review().mainlineResolved()+" ending="+check.review().endingClear()
                +" foreshadow="+check.review().foreshadowingResolved());
        check.review().issueDetails().forEach(issue->System.out.println(
                "LIVE_COMPLETION_ISSUE="+issue.severity()+" "+issue.problem()));
        if(check.review().passed() && check.review().mainlineResolved() && check.review().endingClear()
                && check.review().foreshadowingResolved()) {
            current=workflow.finish(novelId,check.id(),current.revision,true);
            System.out.println("LIVE_COMPLETION_STATUS="+current.status);
        }
        assertThat(current.status).as("完整短篇的完结检查和作者确认").isEqualTo("COMPLETED");
    }

    @Test void realModelWritesFirstChapterFromAnApprovedShortSuspensePlan() {
        Novel novel=workflow.create("雨夜旧照正文验收",
                "顾乔整理亡母遗物，找到一张无日期的旧合照，背面写着“林秋宁”。她想知道母亲为何留下这张照片。",
                6000,"悬疑短篇，重视人物动机和阅读节奏。顾乔开始调查时不知道旧楼曾发生火灾；邻居回避不等于刻意隐瞒。");
        novel=repository.update(novel.id,current->{
            var spec=OutlineTestFixtures.validSpec(6000,
                    "顾乔整理亡母遗物，找到背面署名林秋宁的旧合照和母亲未寄出的纸条。她循照片追查母亲未说出的往事，先经历邻居回避，后从独立资料得知旧楼火灾，最终查清林秋宁的去向。"
                    +"故事从顾乔与母亲的未解关系出发，不能把寻找写成逐站领取档案。");
            spec.genreTone="悬疑短篇、有限视角、克制但有人情张力";
            spec.readerAttraction="顾乔为何收到母亲留下的旧照，林秋宁去了哪里，母亲当年为何没有寄出纸条";
            spec.characters.getFirst().name="顾乔";
            spec.characters.getFirst().goal="查清林秋宁与母亲的关系及她的去向";
            spec.characters.getFirst().motivation="母亲已去世，她不想再错过母亲没说出口的事";
            Artifact outline=new Artifact(); outline.kind=Kind.OUTLINE;
            Version outlineVersion=new Version(); outlineVersion.title="全书大纲";
            outlineVersion.content=outlineRenderer.render(spec); outlineVersion.summary="顾乔由母亲遗物追查林秋宁";
            outlineVersion.outlineSpec=spec; outlineVersion.outlineSpecHash=outlineStructure.hash(spec);
            outline.versions.add(outlineVersion); outline.approvedVersionId=outlineVersion.id;
            Artifact characters=new Artifact(); characters.kind=Kind.CHARACTERS;
            Version setting=new Version(); setting.title="人物设定";
            setting.content="顾乔：母亲刚去世，整理遗物时发现旧照与未寄出的纸条；她想了解母亲隐而未言的一段往事。"
                    +"林秋宁：旧合照背面的署名者，真实去向未明。旧邻居：认得旧楼和顾乔的母亲，但回避林秋宁的问题，其原因尚未证实。";
            setting.summary="顾乔有私人寻找动机；邻居回避原因未明";
            characters.versions.add(setting); characters.approvedVersionId=setting.id;
            Plan plan=new Plan(); plan.startChapter=1; plan.endChapter=3; plan.prepareNextAfterChapter=2;
            plan.finalBatch=false; plan.triggerReason="第二章出现另一条线索后准备后续规划";
            plan.handoff="第三章从独立旧报首次得知火灾，下一批再查林秋宁去向"; plan.assumptions="";
            plan.chapters=List.of(
                    new ChapterBeat(1,"没寄出的信","顾乔在母亲遗物中发现旧照与未寄出的纸条，带着私人疑问去旧楼找人；旧邻居的反应使她决定继续查，但未证实有人隐瞒。",1100,
                            List.of("顾乔整理母亲遗物，找到背面写林秋宁名字的旧合照和一张未寄出的纸条，纸条的具体内容可以留下悬念但要让她有行动理由。",
                                    "顾乔去旧楼询问一位老邻居，对方先认出顾乔的母亲，提到林秋宁时却回避；顾乔没逼问，带着新的疑问离开。"),
                            "本章不知道旧楼火灾；邻居回避只证明不愿回答，不证明刻意隐瞒或涉案。",
                            "顾乔意识到母亲与林秋宁之间有一段自己不知道的往事，决定换一种方式查。"),
                    new ChapterBeat(2,"旧楼里的人","顾乔换个方法寻找当年住户，发现母亲与林秋宁有共同生活的痕迹，同时与旧邻居的关系发生变化。",1100,
                            List.of("顾乔从生活痕迹或熟人谈话中找到一条可核对的新线索，而不只是再翻同一份名单。",
                                    "旧邻居可保持沉默，但对顾乔的处境表现出一点矛盾的关心；顾乔重新判断她的态度。"),
                            "暂不揭示火灾与林秋宁的实际关系。","新线索指向一份可以查阅的旧报。"),
                    new ChapterBeat(3,"那年旧报","顾乔从独立旧报首次得知旧楼火灾，发现自己此前的猜测需要修正。",1100,
                            List.of("顾乔查阅旧报，核实火灾发生的时间和地点。",
                                    "她区分报道确证的事实与尚无依据的林秋宁去向，做出下一步选择。"),
                            "不因火灾报道就断定林秋宁在火灾中失踪。","顾乔带着新问题进入后续调查。"));
            Artifact planning=new Artifact(); planning.kind=Kind.PLAN; planning.batchNumber=1;
            Version planVersion=new Version(); planVersion.title="第一批章节规划";
            planVersion.content=PlanNarrativeRenderer.render(plan); planVersion.summary=PlanNarrativeRenderer.summary(plan);
            planVersion.plan=plan; planning.versions.add(planVersion); planning.approvedVersionId=planVersion.id;
            current.artifacts.addAll(List.of(outline,characters,planning)); return current;
        });

        Task submitted=tasks.submit(novel.id,Action.CHAPTER,null,"",Novel.uid(),novel.revision);
        String novelId=novel.id;
        await().atMost(Duration.ofMinutes(7)).until(()->{
            TaskStatus status=tasks.find(repository.get(novelId),submitted.id).status;
            return status!=TaskStatus.QUEUED && status!=TaskStatus.RUNNING;
        });
        Novel after=repository.get(novelId);
        Task task=tasks.find(after,submitted.id);
        if(task.status==TaskStatus.FAILED && task.resultArtifactId!=null && task.resultVersionId!=null) {
            String savedArtifactId=task.resultArtifactId;
            String savedProse=after.artifacts.stream().filter(a->a.id.equals(savedArtifactId))
                    .findFirst().orElseThrow().latest().content;
            System.out.println("LIVE_CHAPTER_REVIEW_RECOVERY_REASON="+task.error);
            Task retried=tasks.retry(novelId,task.id,Novel.uid(),after.revision);
            await().atMost(Duration.ofMinutes(5)).until(()->{
                TaskStatus status=tasks.find(repository.get(novelId),retried.id).status;
                return status!=TaskStatus.QUEUED && status!=TaskStatus.RUNNING;
            });
            after=repository.get(novelId);
            task=tasks.find(after,retried.id);
            String recoveredProse=after.artifacts.stream().filter(a->a.id.equals(savedArtifactId))
                    .findFirst().orElseThrow().latest().content;
            System.out.println("LIVE_CHAPTER_REVIEW_RECOVERY_PROSE_UNCHANGED="+savedProse.equals(recoveredProse));
            assertThat(recoveredProse).isEqualTo(savedProse);
        }
        System.out.println("LIVE_CHAPTER_TASK="+task.status+" "+task.error);
        Artifact chapter=after.artifacts.stream().filter(a->a.kind==Kind.CHAPTER).findFirst().orElse(null);
        if(chapter!=null) {
            Version version=chapter.latest();
            System.out.println("LIVE_CHAPTER_REVIEW_PASSED="+(version.review!=null&&version.review.passed()));
            if(version.review!=null) version.review.issueDetails().forEach(issue->
                    System.out.println("LIVE_CHAPTER_ISSUE="+issue.severity()+" "+issue.problem()));
            System.out.println("LIVE_CHAPTER_RAW_PROSE="+version.content.replace("\n","\\n"));
        }
        assertThat(task.status).as(task.error).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(chapter).isNotNull();
        boolean summaryOnlyProblem=chapter.latest().review!=null
                && chapter.latest().review.issueDetails().stream()
                .filter(issue->"必须修正".equals(issue.severity()))
                .allMatch(issue->issue.location().contains("摘要"))
                && chapter.latest().review.issueDetails().stream()
                .anyMatch(issue->"必须修正".equals(issue.severity()));
        if(summaryOnlyProblem) {
            String originalProse=chapter.latest().content;
            Task correction=tasks.submitSummary(novelId,chapter.id,Novel.uid(),after.revision);
            await().atMost(Duration.ofMinutes(5)).until(()->{
                TaskStatus status=tasks.find(repository.get(novelId),correction.id).status;
                return status!=TaskStatus.QUEUED && status!=TaskStatus.RUNNING;
            });
            after=repository.get(novelId);
            chapter=after.artifacts.stream().filter(a->a.kind==Kind.CHAPTER).findFirst().orElseThrow();
            Task fixed=tasks.find(after,correction.id);
            System.out.println("LIVE_CHAPTER_SUMMARY_TASK="+fixed.status+" "+fixed.error);
            System.out.println("LIVE_CHAPTER_SUMMARY_BODY_UNCHANGED="+originalProse.equals(chapter.latest().content));
            assertThat(fixed.status).as(fixed.error).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(chapter.latest().content).isEqualTo(originalProse);
        }
        if(chapter.latest().review!=null && chapter.latest().review.passed()) {
            await().atMost(Duration.ofMinutes(4)).until(()->{
                Novel current=repository.get(novelId);
                Artifact currentChapter=current.artifacts.stream().filter(a->a.kind==Kind.CHAPTER).findFirst().orElseThrow();
                return current.tasks.stream().anyMatch(t->t.action==Action.STYLE_REVIEW)
                        && current.tasks.stream().noneMatch(t->t.status==TaskStatus.QUEUED || t.status==TaskStatus.RUNNING)
                        && currentChapter.latest().styleReview!=null;
            });
            Novel polished=repository.get(novelId);
            Artifact currentChapter=polished.artifacts.stream().filter(a->a.kind==Kind.CHAPTER).findFirst().orElseThrow();
            Version latest=currentChapter.latest();
            polished.tasks.stream().filter(t->t.action==Action.STYLE_REVIEW || "STYLE_POLISH".equals(t.automationKind))
                    .forEach(t->System.out.println("LIVE_CHAPTER_STYLE_TASK="+t.action+" "+t.status+" "+t.error));
            System.out.println("LIVE_CHAPTER_STYLE_VERSION="+currentChapter.versions.size());
            latest.styleReview.issueDetails().forEach(issue->
                    System.out.println("LIVE_CHAPTER_STYLE_ISSUE="+issue.severity()+" | "+issue.problem()
                            +" | "+issue.evidence()+" | "+issue.suggestion()));
            System.out.println("LIVE_CHAPTER_STYLE_CAN_AUTO_APPLY="+styleEditor.canApply(latest));
            System.out.println("LIVE_CHAPTER_FINAL_PROSE="+latest.content.replace("\n","\\n"));
            assertThat(latest.review).isNotNull();
            if (latest.review.passed() && latest.draftIssues.isEmpty()) {
                Novel confirmed=workflow.confirm(novelId,currentChapter.id,latest.id,polished.revision,null);
                Task next=tasks.submit(novelId,Action.CHAPTER,null,"",Novel.uid(),confirmed.revision);
                await().atMost(Duration.ofMinutes(7)).until(()->{
                    TaskStatus status=tasks.find(repository.get(novelId),next.id).status;
                    return status!=TaskStatus.QUEUED && status!=TaskStatus.RUNNING;
                });
                Novel second=repository.get(novelId);
                Task secondTask=tasks.find(second,next.id);
                if(secondTask.status==TaskStatus.FAILED && secondTask.resultArtifactId!=null
                        && secondTask.resultVersionId!=null) {
                    String savedArtifactId=secondTask.resultArtifactId;
                    String savedProse=second.artifacts.stream().filter(a->a.id.equals(savedArtifactId))
                            .findFirst().orElseThrow().latest().content;
                    System.out.println("LIVE_SECOND_CHAPTER_REVIEW_RECOVERY_REASON="+secondTask.error);
                    Task retried=tasks.retry(novelId,secondTask.id,Novel.uid(),second.revision);
                    await().atMost(Duration.ofMinutes(5)).until(()->{
                        TaskStatus status=tasks.find(repository.get(novelId),retried.id).status;
                        return status!=TaskStatus.QUEUED && status!=TaskStatus.RUNNING;
                    });
                    second=repository.get(novelId);
                    secondTask=tasks.find(second,retried.id);
                    String recoveredProse=second.artifacts.stream().filter(a->a.id.equals(savedArtifactId))
                            .findFirst().orElseThrow().latest().content;
                    System.out.println("LIVE_SECOND_CHAPTER_REVIEW_RECOVERY_PROSE_UNCHANGED="+savedProse.equals(recoveredProse));
                    assertThat(recoveredProse).isEqualTo(savedProse);
                }
                Artifact secondChapter=second.artifacts.stream()
                        .filter(a->a.kind==Kind.CHAPTER && a.chapterNumber==2).findFirst().orElse(null);
                System.out.println("LIVE_SECOND_CHAPTER_TASK="+secondTask.status+" "+secondTask.error);
                if (secondChapter!=null) {
                    Version secondVersion=secondChapter.latest();
                    System.out.println("LIVE_SECOND_CHAPTER_REVIEW_PASSED="+(secondVersion.review!=null&&secondVersion.review.passed()));
                    if (secondVersion.review!=null) secondVersion.review.issueDetails().forEach(issue->
                            System.out.println("LIVE_SECOND_CHAPTER_ISSUE="+issue.severity()+" "+issue.problem()));
                    System.out.println("LIVE_SECOND_CHAPTER_PROSE="+secondVersion.content.replace("\n","\\n"));
                }
                assertThat(secondTask.status).as(secondTask.error).isEqualTo(TaskStatus.SUCCEEDED);
                assertThat(secondChapter).isNotNull();
            }
        }
    }
}
