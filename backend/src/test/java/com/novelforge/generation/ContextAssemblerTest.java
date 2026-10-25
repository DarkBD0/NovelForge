package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.canon.CanonService;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.novel.WordCounter;
import com.novelforge.outline.OutlineSpec;
import com.novelforge.projection.ElasticsearchShadowSearchService;
import com.novelforge.workflow.WorkflowRules;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextAssemblerTest {
    @Test void plannerAndAuditorSeeOnlyApprovedOutlineStoryEngineFocus() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,1_000_000,
                authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel(); novel.title="旧案"; novel.synopsis="寻找失踪的人";
        novel.targetWords=10000; novel.approvedMaxWords=11000;
        Artifact outline=new Artifact(); outline.kind=Kind.OUTLINE;
        Version approved=new Version(); approved.title="已确认大纲"; approved.content="主角寻找失踪的人";
        approved.summary="追查"; approved.outlineSpec=new OutlineSpec();
        approved.outlineSpec.readerAttraction="主角越接近真相越要面对母亲的选择";
        approved.outlineSpec.protagonistGoal="查明母亲为何隐瞒名单";
        approved.outlineSpec.coreConflict="真相与亲情相撞";
        OutlineSpec.CharacterArc protagonist=new OutlineSpec.CharacterArc();
        protagonist.name="林秋"; protagonist.roleTier="PROTAGONIST";
        protagonist.goal="查清真相"; protagonist.motivation="替母亲澄清";
        approved.outlineSpec.characters.add(protagonist);
        outline.versions.add(approved); outline.approvedVersionId=approved.id;
        Version pending=new Version(); pending.title="未确认候选";
        pending.outlineSpec=new OutlineSpec(); pending.outlineSpec.protagonistGoal="放弃追查";
        outline.versions.add(pending); novel.artifacts.add(outline);

        var request=new ModelGateway.Request(Action.PLAN,novel,null,
                assembler.assemble(novel,Action.PLAN,null),"");
        RoleContextCompiler compiler=new RoleContextCompiler(mapper,new AgentContextPolicy());
        var planner=mapper.readTree(compiler.compile(request,AgentRole.ROLLING_PLANNER).context().json());
        var auditor=mapper.readTree(compiler.compile(request,AgentRole.CONTENT_AUDITOR).context().json());
        var chapterWriter=mapper.readTree(compiler.compile(request,AgentRole.CHAPTER_WRITER).context().json());
        assertThat(planner.path("planStoryEngine").path("sourceVersionId").asText()).isEqualTo(approved.id);
        assertThat(planner.path("planStoryEngine").path("readerAttraction").asText())
                .isEqualTo("主角越接近真相越要面对母亲的选择");
        assertThat(planner.path("planStoryEngine").path("characterArcs").get(0).path("motivation").asText())
                .isEqualTo("替母亲澄清");
        assertThat(planner.path("planStoryEngine").toString()).doesNotContain("放弃追查");
        assertThat(auditor.path("planStoryEngine").path("coreConflict").asText()).isEqualTo("真相与亲情相撞");
        assertThat(chapterWriter.has("planStoryEngine")).isFalse();
    }

    @Test void completionAuditorReadsRecentEndingProseButNotTheEntireNovel() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,1_000_000,
                authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel(); novel.title="最终选择"; novel.synopsis="主角必须在两个工作之间作出选择";
        novel.targetWords=10000; novel.approvedMaxWords=11000;
        Artifact first=chapter(1,"开篇"); first.approved().content="第一章旧正文";
        Artifact second=chapter(2,"抉择前"); second.approved().content="第二章候选人犹豫";
        Artifact third=chapter(3,"尾声"); third.approved().content="第三章主角作出选择";
        novel.artifacts.addAll(List.of(first,second,third));

        var request=new ModelGateway.Request(Action.COMPLETE,novel,null,
                assembler.assemble(novel,Action.COMPLETE,null),"");
        var visible=mapper.readTree(new RoleContextCompiler(mapper,new AgentContextPolicy())
                .compile(request,AgentRole.COMPLETION_AUDITOR).context().json());
        String references=visible.path("acceptedReferences").toString();
        assertThat(visible.path("title").asText()).isEqualTo("最终选择");
        assertThat(references).contains("第二章候选人犹豫","第三章主角作出选择")
                .doesNotContain("第一章旧正文");
        assertThat(references).contains("开篇");
    }

    @Test void nextBatchComparesOnlyApprovedPreviousPlanBeatsAndKeepsThemOutOfChapterWriter() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,1_000_000,
                authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel(); novel.title="跨批次"; novel.synopsis="验证相邻规划";
        novel.targetWords=10000; novel.approvedMaxWords=11000;
        Artifact prior=plan(); novel.artifacts.add(prior);
        var base=assembler.assemble(novel,Action.PLAN,null);
        var request=new ModelGateway.Request(Action.PLAN,novel,null,base,"");
        RoleContextCompiler compiler=new RoleContextCompiler(mapper,new AgentContextPolicy());
        var planner=mapper.readTree(compiler.compile(request,AgentRole.ROLLING_PLANNER).context().json());
        var auditor=mapper.readTree(compiler.compile(request,AgentRole.CONTENT_AUDITOR).context().json());
        var writer=mapper.readTree(compiler.compile(request,AgentRole.CHAPTER_WRITER).context().json());

        assertThat(planner.path("planComparison").path("comparisonStartChapter").asInt()).isEqualTo(4);
        assertThat(planner.path("planComparison").path("previousBeats").size()).isEqualTo(2);
        assertThat(planner.path("planComparison").path("previousBeats").get(1).path("purpose").asText())
                .isEqualTo("发现真相");
        assertThat(planner.path("planComparison").path("previousBeats").get(1).path("sourceVersionId").asText())
                .isEqualTo(prior.approvedVersionId);
        assertThat(auditor.path("planComparison").path("previousBeats").size()).isEqualTo(2);
        assertThat(writer.has("planComparison")).isFalse();
    }

    @Test void rewritingPlanNeverUsesItsOwnOldBeatsAsPreviousBatchEvidence() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,1_000_000,
                authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel(); novel.title="规划修订"; novel.synopsis="修订边界";
        novel.targetWords=10000; novel.approvedMaxWords=11000;
        Artifact prior=plan(); novel.artifacts.add(prior);
        Artifact target=plan(); target.batchNumber=2;
        target.approved().plan.startChapter=4; target.approved().plan.endChapter=5;
        target.approved().plan.chapters=List.of(new ChapterBeat(4,"新章节","目标变化"));
        novel.artifacts.add(target);

        var comparison=mapper.readTree(assembler.assemble(novel,Action.REWRITE,target.id).json())
                .path("planComparison");
        assertThat(comparison.path("comparisonStartChapter").asInt()).isEqualTo(4);
        assertThat(comparison.path("previousBeats").toString()).contains("继续调查","发现真相")
                .doesNotContain("目标变化");
    }

    @Test void chapterWriterReceivesCriticalPlanStateAsConditionalDesignNotOccurredHistory() throws Exception {
        ObjectMapper mapper=new ObjectMapper();WordCounter words=new WordCounter();WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,1_000_000,
                authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel();novel.title="照片线索";novel.synopsis="旧案";
        novel.targetWords=10000;novel.approvedMaxWords=11000;
        Artifact prior=chapter(1,"主角还没找到照片");Artifact design=plan();
        design.approved().plan.criticalTransitions=List.of(new CriticalTransition(2,"旧照片","尚未找到",
                "第二章取得照片","照片由主角持有","正文发生","火灾前拍摄"));
        novel.artifacts.addAll(List.of(prior,design));

        var request=new ModelGateway.Request(Action.CHAPTER,novel,null,
                assembler.assemble(novel,Action.CHAPTER,null),"");
        var visible=mapper.readTree(new RoleContextCompiler(mapper,new AgentContextPolicy())
                .compile(request,AgentRole.CHAPTER_WRITER).context().json());

        assertThat(visible.path("acceptedReferences").toString()).contains("criticalTransitions","火灾前拍摄")
                .contains("FUTURE_CONDITIONAL_DESIGN");
        assertThat(visible.path("previousChapterSummary").asText()).contains("还没找到照片");
    }

    @Test void stateExtractorSeesPriorStableEntityAndRelationIdsWithoutGivingThemToWriter() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,1_000_000,authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel(); novel.title="关系续写"; novel.synopsis="招聘故事"; novel.targetWords=10000; novel.approvedMaxWords=11000;
        Artifact prior=chapter(1,"第一章");
        prior.approved().stateEntities=List.of(new Novel.StateEntity("organization_hongyuan_manufacturing","ORGANIZATION","宏远制造",List.of(),"公司"));
        prior.approved().stateRelations=List.of(new Novel.StateRelation("relation_hanmei_works_for_hongyuan",
                "character_hanmei","WORKS_FOR","organization_hongyuan_manufacturing","韩梅供职宏远制造","ACTIVE"));
        novel.artifacts.add(prior);
        Artifact current=new Artifact(); current.kind=Kind.CHAPTER; current.chapterNumber=2;
        Version draft=new Version(); draft.title="第二章"; draft.content="韩梅在宏远制造工作。"; draft.summary="韩梅仍在宏远制造。";
        current.versions.add(draft); novel.artifacts.add(current);

        var context=assembler.assemble(novel,Action.REVIEW,current.id);
        var request=new ModelGateway.Request(Action.REVIEW,novel,current,context,"");
        RoleContextCompiler compiler=new RoleContextCompiler(mapper,new AgentContextPolicy());
        var extractor=mapper.readTree(compiler.compile(request,AgentRole.STATE_EXTRACTOR).context().json());
        var writer=mapper.readTree(compiler.compile(request,AgentRole.CHAPTER_WRITER).context().json());

        assertThat(extractor.path("knownIdentityHints").toString())
                .contains("organization_hongyuan_manufacturing","relation_hanmei_works_for_hongyuan");
        assertThat(writer.has("knownIdentityHints")).isFalse();
    }

    @Test void outlineRewriteGivesArchitectAndReviewersTheSameOldStoryBoundary() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,1_000_000,authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel(); novel.title="重写大纲"; novel.synopsis="爽文"; novel.targetWords=10000; novel.approvedMaxWords=11000;
        Artifact outline=new Artifact(); outline.kind=Kind.OUTLINE;
        Version outlineVersion=new Version(); outlineVersion.title="旧大纲"; outlineVersion.content="旧版开篇"; outlineVersion.summary="旧版摘要";
        outline.versions.add(outlineVersion); outline.approvedVersionId=outlineVersion.id; novel.artifacts.add(outline);
        novel.artifacts.add(plan()); novel.artifacts.add(chapter(1,"第一章先受挫"));

        var context=assembler.assemble(novel,Action.REWRITE,outline.id);
        var source=mapper.readTree(context.json());
        assertThat(source.path("outlineRevisionBoundary").path("confirmedChapterSummaries").toString())
                .contains("第一章先受挫");
        assertThat(source.path("outlineRevisionBoundary").path("plannedChapterBeats").toString())
                .contains("追查").doesNotContain("旧版开篇");
        ModelGateway.Request request=new ModelGateway.Request(Action.REWRITE,novel,outline,context,"整体重写大纲");
        RoleContextCompiler compiler=new RoleContextCompiler(mapper,new AgentContextPolicy());
        for(AgentRole role:new AgentRole[]{AgentRole.STORY_ARCHITECT,AgentRole.CONTINUITY_AUDITOR,AgentRole.PLOT_FORESHADOW_AUDITOR}) {
            var visible=mapper.readTree(compiler.compile(request,role).context().json());
            assertThat(visible.path("outlineRevisionBoundary").path("confirmedChapterSummaries").toString())
                    .contains("第一章先受挫");
        }
    }
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
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,1_000_000,authority,new GenreNarrativeProfile(authority));
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

    @Test void upstreamOutlineChangeAllowsReplanningInsteadOfHandoffCalibration() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,1_000_000,authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel(); novel.title="改版后重排"; novel.synopsis="大纲已改变"; novel.targetWords=10000; novel.approvedMaxWords=11000;
        Artifact outline=new Artifact(); outline.kind=Kind.OUTLINE;
        Version outlineVersion=new Version(); outlineVersion.title="新大纲"; outlineVersion.content="新大纲内容"; outlineVersion.summary="新方向";
        outline.versions.add(outlineVersion); outline.approvedVersionId=outlineVersion.id; novel.artifacts.add(outline);
        Artifact affected=plan(); affected.needsRevision=true; novel.artifacts.add(affected);
        Change change=new Change(); change.sourceArtifactId=outline.id; change.affectedArtifactIds.add(affected.id); novel.changes.add(change);

        var json=mapper.readTree(assembler.assemble(novel,Action.REWRITE,affected.id).json());

        assertThat(json.path("planningState").path("mode").asText()).isEqualTo("UPSTREAM_REPLAN");
        assertThat(json.path("planningState").path("calibrationBoundary").asText()).contains("允许重排");
        Version pending=new Version(); pending.title="待确认重排"; pending.content="新规划候选"; pending.summary="候选";
        pending.plan=affected.approved().plan; affected.versions.add(pending); affected.needsRevision=false;
        var retry=mapper.readTree(assembler.assemble(novel,Action.REWRITE,affected.id).json());
        assertThat(retry.path("planningState").path("mode").asText()).isEqualTo("UPSTREAM_REPLAN");
    }

    @Test void addsVerifiedRetrievalToChapterWorkButNotStyleReview() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        Novel novel=new Novel(); novel.title="检索上下文"; novel.synopsis="验证"; novel.targetWords=10000; novel.approvedMaxWords=11000;
        Artifact oldChapter=chapter(1,"旧线索摘要"); novel.artifacts.add(oldChapter);
        Artifact plan=plan();
        plan.approved().plan.startChapter=1; plan.approved().plan.endChapter=2;
        plan.approved().plan.chapters=List.of(new ChapterBeat(1,"抢先投递","主角成为唯一候选"),new ChapterBeat(2,"直接面试","拿到录用"));
        novel.artifacts.add(plan);
        ElasticsearchShadowSearchService searches=mock(ElasticsearchShadowSearchService.class);
        var hit=new com.novelforge.projection.ElasticsearchProjectionClient.Hit(oldChapter.approvedVersionId,oldChapter.id,1,
                9,"第一章","旧线索摘要","旧线索原文");
        when(searches.search(eq(novel.id),anyString(),eq(2),eq(5))).thenReturn(new ElasticsearchShadowSearchService.Run(
                "run-1",novel.id,"query",2,5,"SUCCEEDED",1,4,"2026-10-02T00:00:00Z",null,List.of(hit)));
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,1_000_000,authority,new GenreNarrativeProfile(authority));
        assembler.setRetrievalContexts(new RetrievalContextService(searches,true,5,6000));

        var chapterJson=mapper.readTree(assembler.assemble(novel,Action.CHAPTER,null).json());
        var styleJson=mapper.readTree(assembler.assemble(novel,Action.STYLE_REVIEW,oldChapter.id).json());

        assertThat(chapterJson.path("retrievedConfirmedHistory").path("status").asText()).isEqualTo("APPLIED");
        assertThat(chapterJson.path("retrievedConfirmedHistory").path("hits").get(0).path("excerpt").asText())
                .isEqualTo("旧线索原文");
        assertThat(styleJson.has("retrievedConfirmedHistory")).isFalse();
    }

    @Test void usesShuangStyleInsteadOfHardCodedSuspenseForChapterBrief() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,1_000_000,authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel(); novel.title="重生秋招爽文"; novel.synopsis="主角利用信息差拿到全部录用通知";
        novel.requirements="这是纯粹的爽文，不要写成悬疑调查"; novel.targetWords=10000; novel.approvedMaxWords=11000;
        Artifact plan=plan();
        plan.approved().plan.startChapter=1; plan.approved().plan.endChapter=2;
        plan.approved().plan.chapters=List.of(new ChapterBeat(1,"抢先投递","主角成为唯一候选"),new ChapterBeat(2,"直接面试","拿到录用"));
        novel.artifacts.add(plan);

        var json=mapper.readTree(assembler.assemble(novel,Action.CHAPTER,null).json());

        assertThat(json.path("genreContract").path("mode").asText()).isEqualTo("SHUANG");
        assertThat(json.path("chapterBrief").path("styleMode").asText()).contains("爽文").doesNotContain("保留悬疑");
    }

    @Test void chapterBriefForegroundsRecentConfirmedEndingsWithoutTreatingDraftAsHistory() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,1_000_000,authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel(); novel.title="求职爽文"; novel.synopsis="连续取得机会";
        novel.targetWords=10000; novel.approvedMaxWords=11000;
        Artifact plan=plan(); plan.approved().plan.startChapter=1; plan.approved().plan.endChapter=3;
        plan.approved().plan.chapters=List.of(new ChapterBeat(1,"第一份","拿到机会"),
                new ChapterBeat(2,"第二份","室友问了几家"),new ChapterBeat(3,"第一次签字","签下第一份"));
        novel.artifacts.add(plan);
        Artifact first=chapter(1,"主角拿到第一份意向"); first.approved().content="主角离开公司。";
        Artifact second=chapter(2,"室友已经问过公司数量"); second.approved().content="室友问：你到底有几家？主角答：七家。";
        novel.artifacts.add(first); novel.artifacts.add(second);
        Artifact draft=new Artifact(); draft.kind=Kind.CHAPTER; draft.chapterNumber=3;
        Version draftVersion=new Version(); draftVersion.content="未确认的第三章收尾"; draft.versions.add(draftVersion);
        novel.artifacts.add(draft);

        var json=mapper.readTree(assembler.assemble(novel,Action.CHAPTER,null).json());
        var progress=json.path("chapterBrief").path("recentChapterProgress");

        assertThat(progress.size()).isEqualTo(2);
        assertThat(progress.get(0).path("chapter").asInt()).isEqualTo(1);
        assertThat(progress.get(1).path("actualOutcome").asText()).contains("室友已经问过");
        assertThat(progress.get(1).path("endingExcerpt").asText()).contains("主角答：七家");
        assertThat(progress.toString()).doesNotContain("未确认的第三章收尾");
        assertThat(json.path("chapterBrief").path("planEchoResolution").asText())
                .contains("不必展示普通交接")
                .contains("之后再揭露");
    }

    @Test void chapterBriefSurfacesBuriedNextDayPromiseAsCue() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,1_000_000,authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel(); novel.title="悬疑试写"; novel.synopsis="追查旧事";
        novel.targetWords=10000; novel.approvedMaxWords=11000;
        Artifact plan=plan(); plan.approved().plan.startChapter=1; plan.approved().plan.endChapter=3;
        plan.approved().plan.chapters=List.of(new ChapterBeat(1,"开端","发现旧屋"),new ChapterBeat(2,"调查","询问旧友"),new ChapterBeat(3,"名单","找到名单"));
        novel.artifacts.add(plan);
        Artifact second=chapter(2,"主角询问旧友");
        second.approved().content="老吴说：明天把还记得的名单给你。主角道谢离开。后来雨又下了起来。";
        novel.artifacts.add(second);

        var json=mapper.readTree(assembler.assemble(novel,Action.CHAPTER,null).json());
        var cues=json.path("chapterBrief").path("recentChapterProgress").get(0).path("commitmentCues");

        assertThat(cues.toString()).contains("明天把还记得的名单给你");
    }

    @Test void planRewriteCanSeeChaptersConfirmedAfterThePlan() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,1_000_000,authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel(); novel.title="规划校准"; novel.synopsis="验证历史可见"; novel.targetWords=10000; novel.approvedMaxWords=11000;
        Artifact oldPlan=plan(); novel.artifacts.add(oldPlan);
        novel.artifacts.add(chapter(1,"第一章已经发生"));

        var json=mapper.readTree(assembler.assemble(novel,Action.REWRITE,oldPlan.id).json());

        assertThat(json.path("acceptedReferences").toString()).contains("第一章已经发生").contains("OCCURRED");
    }

    @Test void planningUsesObservedApprovedChapterLengthNotAnUnconfirmedDraft() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,1_000_000,authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel(); novel.title="篇幅校准"; novel.synopsis="避免把结局压进过长章节";
        novel.targetWords=50000; novel.approvedMaxWords=55000;
        Artifact plan=plan(); novel.artifacts.add(plan);
        Artifact first=chapter(1,"已写短章"); first.approved().content="一二三四五。"; novel.artifacts.add(first);
        Artifact second=chapter(2,"已写长章"); second.approved().content="甲乙丙丁戊己庚。"; novel.artifacts.add(second);
        Artifact draft=new Artifact(); draft.kind=Kind.CHAPTER; draft.chapterNumber=3;
        Version draftVersion=new Version(); draftVersion.content="这段草稿还没确认，不应算入平均字数"; draft.versions.add(draftVersion);
        novel.artifacts.add(draft);

        var json=mapper.readTree(assembler.assemble(novel,Action.REWRITE,plan.id).json());
        assertThat(json.path("planningState").path("recentConfirmedChapterWords").size()).isEqualTo(2);
        assertThat(json.path("planningState").path("recentConfirmedChapterWords").get(0).path("words").asInt()).isEqualTo(5);
        assertThat(json.path("planningState").path("recentAverageChapterWords").asInt()).isEqualTo(6);
    }

    @Test void plannerSeesBudgetAfterReservingUnwrittenPriorBatch() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,100_000,
                authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel(); novel.title="短篇预算"; novel.synopsis="完整结局";
        novel.targetWords=5000; novel.approvedMaxWords=5500;
        for(int number=1;number<=3;number++) {
            Artifact written=chapter(number,"已确认"); written.approved().content="字".repeat(1000);
            novel.artifacts.add(written);
        }
        Artifact previous=plan();
        previous.approved().plan.chapters=List.of(new ChapterBeat(2,"已写","已写",1000,List.of(),"",""),
                new ChapterBeat(3,"已写","已写",1000,List.of(),"",""),
                new ChapterBeat(4,"未写","未写",1000,List.of(),"",""));
        previous.approved().plan.endChapter=4;
        novel.artifacts.add(previous);

        var request=new ModelGateway.Request(Action.PLAN,novel,null,assembler.assemble(novel,Action.PLAN,null),"");
        var compiled=new RoleContextCompiler(mapper,new AgentContextPolicy()).compile(request,AgentRole.ROLLING_PLANNER);
        var budget=mapper.readTree(compiled.context().json()).path("planningWordBudget");

        assertThat(budget.path("confirmedWords").asLong()).isEqualTo(3000);
        assertThat(budget.path("priorUnwrittenSuggestedWords").asLong()).isEqualTo(1000);
        assertThat(budget.path("availableForThisAndLaterBatches").asLong()).isEqualTo(1500);
    }

    @Test void chapterReviewKeepsActivePlanAndOutlineWithoutRepeatingOldBatchBodiesOrOutlineSpec() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,100_000,authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel(); novel.title="长篇上下文"; novel.synopsis="续写检查";
        novel.targetWords=50_000; novel.approvedMaxWords=55_000;
        Artifact outline=new Artifact(); outline.kind=Kind.OUTLINE;
        Version outlineVersion=new Version(); outlineVersion.content="已确认的故事规则";
        outlineVersion.summary="大纲摘要";
        outlineVersion.outlineSpec=new com.novelforge.outline.OutlineSpec();
        outline.versions.add(outlineVersion); outline.approvedVersionId=outlineVersion.id; novel.artifacts.add(outline);
        Artifact oldPlan=plan(); oldPlan.batchNumber=1;
        oldPlan.approved().content="旧批次完整规划内容"; novel.artifacts.add(oldPlan);
        Artifact activePlan=plan(); activePlan.batchNumber=2;
        activePlan.approved().plan.startChapter=4; activePlan.approved().plan.endChapter=5;
        activePlan.approved().plan.chapters=List.of(new ChapterBeat(4,"下一章","新批次推进"),new ChapterBeat(5,"后续","收束"));
        activePlan.approved().content="当前批次完整规划内容"; novel.artifacts.add(activePlan);
        novel.artifacts.add(chapter(1,"已确认第一章"));
        Artifact draft=new Artifact(); draft.kind=Kind.CHAPTER; draft.chapterNumber=4;
        Version candidate=new Version(); candidate.content="待检查的第四章"; draft.versions.add(candidate); novel.artifacts.add(draft);

        var context=mapper.readTree(assembler.assemble(novel,Action.REVIEW,draft.id).json());
        var references=context.path("acceptedReferences");
        assertThat(references.toString()).contains("已确认的故事规则").doesNotContain("旧批次完整规划内容");
        var old=java.util.stream.StreamSupport.stream(references.spliterator(),false)
                .filter(node->oldPlan.approvedVersionId.equals(node.path("versionId").asText())).findFirst().orElseThrow();
        var active=java.util.stream.StreamSupport.stream(references.spliterator(),false)
                .filter(node->activePlan.approvedVersionId.equals(node.path("versionId").asText())).findFirst().orElseThrow();
        var outlineRef=java.util.stream.StreamSupport.stream(references.spliterator(),false)
                .filter(node->outline.approvedVersionId.equals(node.path("versionId").asText())).findFirst().orElseThrow();
        assertThat(old.has("content")).isFalse();
        assertThat(old.has("plan")).isFalse();
        assertThat(active.has("plan")).isTrue();
        assertThat(active.has("content")).isFalse();
        assertThat(outlineRef.has("content")).isTrue();
        assertThat(outlineRef.has("outlineSpec")).isFalse();
    }

    @Test void planRewriteKeepsStructuredTargetAndCurrentBatchWithoutDuplicatingRenderedPlans() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,100_000,authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel(); novel.title="批次规划去重"; novel.synopsis="提前规划";
        novel.targetWords=50_000; novel.approvedMaxWords=55_000;
        Artifact previous=plan(); previous.batchNumber=1;
        previous.approved().plan.startChapter=1; previous.approved().plan.endChapter=2;
        previous.approved().plan.chapters=List.of(new ChapterBeat(1,"已写","兑现"),new ChapterBeat(2,"当前","承接"));
        novel.artifacts.add(previous);
        Artifact target=plan(); target.batchNumber=2;
        target.approvedVersionId=null;
        target.versions.getFirst().content="候选规划的完整渲染文本";
        novel.artifacts.add(target);
        novel.artifacts.add(chapter(1,"已确认的第一章"));

        var json=mapper.readTree(assembler.assemble(novel,Action.REWRITE,target.id).json());
        var previousRef=java.util.stream.StreamSupport.stream(json.path("acceptedReferences").spliterator(),false)
                .filter(node->previous.approvedVersionId.equals(node.path("versionId").asText())).findFirst().orElseThrow();
        assertThat(previousRef.has("plan")).isTrue();
        assertThat(previousRef.has("content")).isFalse();
        assertThat(json.path("revisionTarget").has("plan")).isTrue();
        assertThat(json.path("revisionTarget").has("content")).isFalse();
    }

    @Test void olderEventDetailsUseApprovedSummariesWhileDurableFactsAndRecentEventsRemain() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,100_000,authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel(); novel.title="长期档案"; novel.synopsis="长篇";
        novel.targetWords=50_000; novel.approvedMaxWords=55_000;
        Artifact early=chapter(1,"早期事件已经发生，摘要保留细节");
        early.approved().facts=List.of(new Fact("old-event","EVENT","早期一次性事件细节","ACTIVE"),
                new Fact("old-rule","WORLD","持续有效的世界规则","ACTIVE"),
                new Fact("old-hook","FORESHADOW","尚未回收的伏笔","OPEN"));
        novel.artifacts.add(early);
        for(int number=2;number<=7;number++) novel.artifacts.add(chapter(number,"第"+number+"章摘要"));
        Artifact recent=novel.artifacts.getLast();
        recent.approved().facts=List.of(new Fact("recent-event","EVENT","最近事件细节","ACTIVE"));

        var json=mapper.readTree(assembler.assemble(novel,Action.CHAPTER,null).json());
        String visible=json.path("canonBeforeChapter").toString();
        assertThat(visible).doesNotContain("早期一次性事件细节")
                .contains("持续有效的世界规则","尚未回收的伏笔","最近事件细节");
        assertThat(json.path("acceptedReferences").toString()).contains("早期事件已经发生，摘要保留细节");
        assertThat(json.path("stateModel").path("olderEventCoverage").asText()).contains("已确认摘要");
    }

    @Test void endingGoalFlowsIntoPlannerChapterAndCompletionWithoutTreatingPlanAsOccurred() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); WordCounter words=new WordCounter(); WorkflowRules rules=new WorkflowRules(words);
        RequirementAuthority authority=new RequirementAuthority();
        ContextAssembler assembler=new ContextAssembler(new CanonService(),rules,words,mapper,100_000,
                authority,new GenreNarrativeProfile(authority));
        Novel novel=new Novel(); novel.title="寻人"; novel.synopsis="寻找姐姐";
        novel.targetWords=5000; novel.approvedMaxWords=5500;
        Artifact outline=new Artifact(); outline.kind=Kind.OUTLINE;
        Version outlineVersion=new Version(); outlineVersion.content="寻找姐姐";
        outlineVersion.outlineSpec=new OutlineSpec(); outlineVersion.outlineSpec.endingContract="查清具体去向";
        OutlineSpec.EndingGoal goal=new OutlineSpec.EndingGoal(); goal.id="where";
        goal.goal="查清姐姐的具体去向"; goal.proofStandard="可信信息能确定所在城市";
        outlineVersion.outlineSpec.endingGoals=List.of(goal);
        outline.versions.add(outlineVersion); outline.approvedVersionId=outlineVersion.id; novel.artifacts.add(outline);
        Artifact plan=plan(); plan.approved().plan.startChapter=1; plan.approved().plan.endChapter=1;
        plan.approved().plan.chapters=List.of(new ChapterBeat(1,"找到人","主角联系姐姐"));
        plan.approved().plan.finalBatch=true;
        plan.approved().plan.resolutionSteps=List.of(new ResolutionStep("where",1,"联系本人","本人亲口确认",
                "知道姐姐所在城市","不能推断详细住址"));
        novel.artifacts.add(plan);
        RoleContextCompiler compiler=new RoleContextCompiler(mapper,new AgentContextPolicy());

        var planRequest=new ModelGateway.Request(Action.PLAN,novel,null,
                assembler.assemble(novel,Action.PLAN,null),"");
        var planner=mapper.readTree(compiler.compile(planRequest,AgentRole.ROLLING_PLANNER).context().json());
        assertThat(planner.path("planStoryEngine").path("endingGoals").toString())
                .contains("查清姐姐的具体去向","可信信息能确定所在城市");

        var chapterRequest=new ModelGateway.Request(Action.CHAPTER,novel,null,
                assembler.assemble(novel,Action.CHAPTER,null),"");
        var writer=mapper.readTree(compiler.compile(chapterRequest,AgentRole.CHAPTER_WRITER).context().json());
        var auditor=mapper.readTree(compiler.compile(chapterRequest,AgentRole.CONTENT_AUDITOR).context().json());
        assertThat(writer.path("currentChapterPlan").path("resolutionRequirements").toString())
                .contains("本人亲口确认","知道姐姐所在城市");
        assertThat(auditor.path("currentChapterPlan").path("acceptanceItems").toString())
                .contains("结局目标");

        var completeRequest=new ModelGateway.Request(Action.COMPLETE,novel,null,
                assembler.assemble(novel,Action.COMPLETE,null),"");
        var completion=mapper.readTree(compiler.compile(completeRequest,AgentRole.COMPLETION_AUDITOR).context().json());
        assertThat(completion.path("completionGoals").toString()).contains("可信信息能确定所在城市");
        assertThat(completion.has("planStoryEngine")).isFalse();
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
