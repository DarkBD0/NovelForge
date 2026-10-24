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
import com.novelforge.workflow.WorkflowService;
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
    @Autowired TaskService tasks;
    @Autowired OutlineRenderer outlineRenderer;
    @Autowired OutlineStructureService outlineStructure;
    @Autowired DeterministicStyleEditor styleEditor;
    @Autowired ModelGateway modelGateway;

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

    @Test void realModelPlansAndReviewsAgainstIsolatedNovel() {
        boolean personalStake=Boolean.getBoolean("novelforge.acceptance.personalStake");
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

        if (!plan.latest().review.passed() || !plan.latest().draftIssues.isEmpty()) {
            System.out.println("LIVE_CHAIN_STOP=规划尚不能确认");
            return;
        }
        Novel current=workflow.confirm(novelId,plan.id,plan.latest().id,after.revision,null);
        for (int chapterNumber=1;chapterNumber<=2;chapterNumber++) {
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
            if (finished.status!=TaskStatus.SUCCEEDED || version.review==null || !version.review.passed()
                    || !version.draftIssues.isEmpty()) break;
            current=workflow.confirm(novelId,chapter.id,version.id,current.revision,null);
        }
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
