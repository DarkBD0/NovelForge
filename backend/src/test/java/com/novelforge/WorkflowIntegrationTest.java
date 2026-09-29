package com.novelforge;

import com.novelforge.generation.ContextAssembler;
import com.novelforge.generation.ReviewPolicy;
import com.novelforge.generation.StyleReviewPolicy;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.novel.WordCounter;
import com.novelforge.task.TaskService;
import com.novelforge.workflow.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:workflow;DB_CLOSE_DELAY=-1")
class WorkflowIntegrationTest {
    @Autowired WorkflowService workflow;
    @Autowired WorkflowRules rules;
    @Autowired NovelRepository repository;
    @Autowired TaskService tasks;
    @Autowired ContextAssembler contexts;
    @Autowired WordCounter words;

    Novel create() { return workflow.create("雾港来信", "一名修船师寻找失踪的父亲", 1000, "逻辑自洽，回收伏笔"); }
    Task run(String id, Action action, String target) {
        Novel n=repository.get(id);
        Task t=tasks.submit(id,action,target,"",Novel.uid(),n.revision);
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            var status=tasks.find(repository.get(id),t.id).status;
            return status!=TaskStatus.RUNNING && status!=TaskStatus.QUEUED;
        });
        await().atMost(Duration.ofSeconds(10)).until(() -> repository.get(id).tasks.stream()
                .noneMatch(task->task.status==TaskStatus.RUNNING || task.status==TaskStatus.QUEUED));
        Task result=tasks.find(repository.get(id),t.id);
        assertThat(result.status).as("任务错误：%s", result.error).isEqualTo(TaskStatus.SUCCEEDED);
        return result;
    }
    Novel approve(String id) {
        Novel n=repository.get(id); Artifact a=rules.pending(n);
        return workflow.confirm(id,a.id,a.latest().id,n.revision,null);
    }
    Novel generateApprove(String id, Action action) { run(id,action,null); return approve(id); }

    @Test void completeFiveChapterLoopWithEarlyNextPlanAndExplicitFinalApproval() {
        Novel n=create();
        run(n.id,Action.OUTLINE,null);
        Novel draft=repository.get(n.id);
        assertThatThrownBy(() -> tasks.submit(n.id,Action.CHARACTERS,null,"",Novel.uid(),draft.revision)).hasMessageContaining("确认当前版本");
        approve(n.id);
        generateApprove(n.id,Action.CHARACTERS);
        generateApprove(n.id,Action.PLAN);
        generateApprove(n.id,Action.CHAPTER); // first chapter, not all three
        assertThat(rules.nextAction(repository.get(n.id))).isEqualTo("PLAN");
        var earlyPlanContext=contexts.assemble(repository.get(n.id),Action.PLAN,null);
        assertThat(earlyPlanContext.json())
                .contains("\"nextChapter\":2")
                .contains("\"nextBatchStartChapter\":4")
                .contains("新批次必须从 nextBatchStartChapter 开始");
        generateApprove(n.id,Action.PLAN);
        Novel early=repository.get(n.id);
        assertThat(rules.nextChapter(early)).isEqualTo(2);
        assertThat(rules.plans(early)).hasSize(2);
        var context=contexts.assemble(early,Action.CHAPTER,null);
        assertThat(context.sourceVersions()).contains(rules.plans(early).getLast().approvedVersionId);
        assertThat(context.json()).contains("\"currentChapterPlan\"","\"acceptanceItems\"","\"chapterBrief\"","\"coreChange\"",
                "\"sceneBeats\"","\"recommendedWords\"","\"binding\":false","\"revealBoundary\"","\"endingHook\"",
                "\"previousChapterSummary\"","\"remainingWordsToTarget\"","\"remainingWordsToApprovedMax\"");
        for (int i=2;i<=5;i++) generateApprove(n.id,Action.CHAPTER);
        Novel written=repository.get(n.id);
        assertThat(words.approvedWords(written)).isEqualTo(1050);
        assertThat(rules.nextAction(written)).isEqualTo("COMPLETE");
        run(n.id,Action.COMPLETE,null);
        Novel checked=repository.get(n.id);
        assertThat(checked.status).isEqualTo("WRITING");
        assertThat(workflow.finish(n.id,checked.completionChecks.getLast().id(),checked.revision,true).status).isEqualTo("COMPLETED");
    }
    @Test void requestsAreIdempotentAndVersionsCannotBeConfirmedOutOfOrder() {
        Novel n=create(); String key=Novel.uid();
        Task t=tasks.submit(n.id,Action.OUTLINE,null,"",key,n.revision);
        assertThat(tasks.submit(n.id,Action.OUTLINE,null,"",key,n.revision).id).isEqualTo(t.id);
        await().atMost(Duration.ofSeconds(10)).until(() -> tasks.find(repository.get(n.id),t.id).status==TaskStatus.SUCCEEDED);
        Novel draft=repository.get(n.id); Artifact a=rules.pending(draft);
        assertThatThrownBy(() -> workflow.confirm(n.id,a.id,a.latest().id,0,null)).hasMessageContaining("过期");
        Novel approved=approve(n.id);
        assertThat(workflow.confirm(n.id,a.id,a.latest().id,0,null).revision).isEqualTo(approved.revision);
        assertThat(repository.get(n.id).artifacts).hasSize(1);
    }
    @Test void editingTailInvalidatesAlreadyCreatedFuturePlanAndRepairsWithoutDeadlock() {
        Novel n=create();
        generateApprove(n.id,Action.OUTLINE); generateApprove(n.id,Action.CHARACTERS);
        generateApprove(n.id,Action.PLAN); generateApprove(n.id,Action.CHAPTER);
        generateApprove(n.id,Action.PLAN); generateApprove(n.id,Action.CHAPTER);
        Novel before=repository.get(n.id);
        Artifact chapter=before.artifacts.stream().filter(a->a.kind==Kind.CHAPTER&&a.chapterNumber==2).findFirst().orElseThrow();
        Artifact futurePlan=rules.plans(before).getLast();
        Version v=chapter.latest();
        workflow.edit(n.id,chapter.id,new WorkflowService.Edit(v.id,v.title,v.content+"\n许岚重新核对日期。",v.summary+"重新核对日期。",v.facts,null,"调整调查过程",before.revision));
        Novel edited=repository.get(n.id);
        assertThat(rules.pending(edited).id).isEqualTo(chapter.id);
        assertThat(rules.artifact(edited,futurePlan.id).needsRevision).isTrue();
        assertThat(rules.artifact(edited,chapter.id).versions).hasSize(2);
        assertThatThrownBy(()->workflow.confirm(n.id,chapter.id,rules.artifact(edited,chapter.id).latest().id,edited.revision,null)).hasMessageContaining("检查");
        run(n.id,Action.REVIEW,chapter.id); approve(n.id);
        Novel sourceConfirmed=repository.get(n.id);
        assertThat(rules.pending(sourceConfirmed).id).isEqualTo(futurePlan.id);
        var ctx=contexts.assemble(sourceConfirmed,Action.REWRITE,futurePlan.id);
        assertThat(ctx.sourceVersions()).contains(rules.artifact(sourceConfirmed,chapter.id).approvedVersionId);
        run(n.id,Action.REWRITE,futurePlan.id); approve(n.id);
        assertThat(rules.nextAction(repository.get(n.id))).isEqualTo("CHAPTER");
    }
    @Test void budgetCannotBeBypassedByReviewOverrideAndNeedsExplicitExpansion() {
        Novel n=create();
        generateApprove(n.id,Action.OUTLINE);generateApprove(n.id,Action.CHARACTERS);generateApprove(n.id,Action.PLAN);
        run(n.id,Action.CHAPTER,null);
        Novel before=repository.get(n.id);Artifact a=rules.pending(before);Version v=a.latest();
        workflow.edit(n.id,a.id,new WorkflowService.Edit(v.id,v.title,"字".repeat(1101),"测试超出预算",v.facts,null,"超预算边界测试",before.revision));
        run(n.id,Action.REVIEW,a.id);
        Novel over=repository.get(n.id);
        assertThatThrownBy(()->workflow.confirm(n.id,a.id,rules.artifact(over,a.id).latest().id,over.revision,"用户愿意接受检查意见")).hasMessageContaining("上限");
        Novel expanded=workflow.budget(n.id,1200,"用户批准扩充",over.revision);
        assertThat(expanded.budgetChanges).hasSize(1);
        assertThatThrownBy(()->workflow.confirm(n.id,a.id,rules.artifact(expanded,a.id).latest().id,expanded.revision,null)).hasMessageContaining("检查");
        run(n.id,Action.REVIEW,a.id);approve(n.id);
        assertThat(words.approvedWords(repository.get(n.id))).isEqualTo(1101);
    }
    @Test void targetAndUpperLimitCanBeChangedWithoutReopeningCompletedNovel() {
        Novel n=create();
        Novel changed=workflow.budget(n.id,900L,1300,"根据剧情调整篇幅",n.revision);
        assertThat(changed.targetWords).isEqualTo(900);
        assertThat(changed.approvedMaxWords).isEqualTo(1300);
        assertThat(changed.budgetChanges.getLast().previousTarget()).isEqualTo(1000);
        assertThat(changed.budgetChanges.getLast().newTarget()).isEqualTo(900);

        Novel completed=repository.update(n.id,stored->{ stored.status="COMPLETED"; return stored; });
        Novel afterCompletion=workflow.budget(n.id,1100L,1400,"完结后修正参考篇幅",completed.revision);
        assertThat(afterCompletion.status).isEqualTo("COMPLETED");
        assertThat(rules.nextAction(afterCompletion)).isEqualTo("COMPLETED");
        assertThat(afterCompletion.completionChecks).isEmpty();
    }
    @Test void wordSettingsRejectAnUpperLimitBelowTargetOrConfirmedContent() {
        Novel n=create();
        assertThatThrownBy(()->workflow.budget(n.id,1200L,1100,"错误设置",n.revision))
                .hasMessageContaining("上限必须大于目标");

        Artifact chapter=new Artifact();chapter.kind=Kind.CHAPTER;chapter.chapterNumber=1;
        Version version=new Version();version.content="字".repeat(1050);chapter.versions.add(version);chapter.approvedVersionId=version.id;
        repository.update(n.id,stored->{ stored.artifacts.add(chapter); return stored; });
        Novel written=repository.get(n.id);
        assertThatThrownBy(()->workflow.budget(n.id,800L,1000,"低于已有正文",written.revision))
                .hasMessageContaining("不小于已确认字数");
    }
    @Test void contextsAreNovelIsolatedAndDoNotLeakFutureFactsIntoEarlierRevision() {
        Novel first=create();Novel other=workflow.create("另一个完全不同的世界","秘密设置不可泄漏",1000,"");
        generateApprove(first.id,Action.OUTLINE);generateApprove(first.id,Action.CHARACTERS);
        generateApprove(first.id,Action.PLAN);generateApprove(first.id,Action.CHAPTER);generateApprove(first.id,Action.PLAN);
        for(int i=2;i<=4;i++)generateApprove(first.id,Action.CHAPTER);
        Novel n=repository.get(first.id);
        Artifact firstChapter=n.artifacts.stream().filter(a->a.kind==Kind.CHAPTER&&a.chapterNumber==1).findFirst().orElseThrow();
        String json=contexts.assemble(n,Action.REWRITE,firstChapter.id).json();
        assertThat(json).doesNotContain("秘密设置不可泄漏").doesNotContain("钥匙打开维护间，找到证据");
        assertThatThrownBy(()->rules.artifact(repository.get(other.id),firstChapter.id)).hasMessageContaining("不属于");
    }
    @Test void completionBudgetAllowsBelowTargetButRejectsAboveApprovedMaximum() {
        Novel n=create();
        Artifact chapter=new Artifact();chapter.kind=Kind.CHAPTER;chapter.chapterNumber=1;
        Version version=new Version();version.content="字".repeat(900);chapter.versions.add(version);chapter.approvedVersionId=version.id;
        n.artifacts.add(chapter);
        assertThatCode(()->rules.validateCompletionBudget(n)).doesNotThrowAnyException();
        n.approvedMaxWords=899;
        assertThatThrownBy(()->rules.validateCompletionBudget(n)).hasMessageContaining("超过已批准上限");
    }
    @Test void characterFactsCannotDuplicatePlotOrderOrInternalKeys() {
        Novel n=create(); Artifact characters=new Artifact(); characters.kind=Kind.CHARACTERS;
        Version version=new Version(); version.title="人物设定"; version.content="王阿姨是邻居，张洋是同学。"; version.summary="人物关系摘要";
        version.facts=List.of(new Fact("character_wang","CHARACTER","王阿姨是邱天初次窥探的对象","ACTIVE"));
        assertThatThrownBy(()->rules.validateVersion(n,characters,version))
                .hasMessageContaining("档案条目“王阿姨是邱天初次窥探的对象”","剧情顺序词“初次”","本次修订未保存")
                .hasMessageNotContaining("character_wang");
        version.facts=List.of(new Fact("event_first","EVENT","张洋先查看手机","ACTIVE"));
        assertThatThrownBy(()->rules.validateVersion(n,characters,version)).hasMessageContaining("只能记录人物静态设定或世界规则");
        version.facts=List.of(new Fact("character_wang","CHARACTER","王阿姨是住在邱天隔壁的邻居","ACTIVE"),
                new Fact("character_wang","CHARACTER","王阿姨习惯把现金放在家中","ACTIVE"));
        assertThatThrownBy(()->rules.validateVersion(n,characters,version)).hasMessageContaining("重复的内部编号");
        version.facts=List.of(new Fact("character_wang","CHARACTER","王阿姨是住在邱天隔壁的邻居","ACTIVE"));
        assertThatCode(()->rules.validateVersion(n,characters,version)).doesNotThrowAnyException();
    }
    @Test void characterRewriteCanPreserveUntouchedLegacyFactsButCannotAddNewOnes() {
        Novel n=create(); Artifact characters=new Artifact(); characters.kind=Kind.CHARACTERS;
        Version base=new Version(); base.title="旧人物设定"; base.content="旧版本内容"; base.summary="旧版本摘要";
        Fact legacy=new Fact("legacy_timeline","TIMELINE","第一章发生旧事件","ACTIVE");
        base.facts=List.of(legacy,new Fact("character_wang","CHARACTER","王阿姨是邱天初次窥探的对象","ACTIVE"));
        characters.versions.add(base);

        Version corrected=new Version(); corrected.baseVersionId=base.id; corrected.title=base.title; corrected.content=base.content; corrected.summary=base.summary;
        corrected.facts=List.of(legacy,new Fact("character_wang","CHARACTER","王阿姨是宿舍楼管理员，爱占小便宜并藏有私房钱","ACTIVE"));
        assertThatCode(()->rules.validateVersion(n,characters,corrected)).doesNotThrowAnyException();

        corrected.facts=List.of(new Fact("new_timeline","TIMELINE","第二章发生新事件","ACTIVE"));
        assertThatThrownBy(()->rules.validateVersion(n,characters,corrected)).hasMessageContaining("只能记录人物静态设定或世界规则");
    }
    @Test void visibleProseRejectsInternalIdentifiersButAllowsNormalEnglish() {
        Novel n=create(); Artifact plan=new Artifact(); plan.kind=Kind.OUTLINE;
        Version version=new Version(); version.title="AI 时代"; version.content="Alice 在 London 调查案件。";
        version.summary="本批为最终批次。"; version.facts=List.of();
        assertThatCode(()->rules.validateVersion(n,plan,version)).doesNotThrowAnyException();
        version.summary="终章已经结束，finalBatch=true。";
        assertThatThrownBy(()->rules.validateVersion(n,plan,version))
                .hasMessageContaining("摘要","系统内部标识“finalBatch”","自然语言");
    }
    @Test void noOpManualEditDoesNotCreateVersionOrInvalidateFollowingContent() {
        Novel n=create();generateApprove(n.id,Action.OUTLINE);generateApprove(n.id,Action.CHARACTERS);
        Novel before=repository.get(n.id);Artifact outline=before.artifacts.getFirst();Version v=outline.latest();
        int versions=outline.versions.size(),changes=before.changes.size();long revision=before.revision;
        assertThatThrownBy(()->workflow.edit(before.id,outline.id,new WorkflowService.Edit(v.id,v.title,v.content,v.summary,v.facts,v.plan,"没有实际修改",revision)))
                .hasMessageContaining("没有发生变化");
        Novel after=repository.get(n.id);
        assertThat(after.revision).isEqualTo(revision);
        assertThat(after.artifacts.getFirst().versions).hasSize(versions);
        assertThat(after.changes).hasSize(changes);
        assertThat(after.artifacts.get(1).needsRevision).isFalse();
    }

    @Test void anOldReviewPolicyCannotConfirmButCanBeRecheckedWithoutChangingContent() {
        Novel n=create(); run(n.id,Action.OUTLINE,null);
        Novel current=repository.update(n.id,stored->{
            stored.artifacts.getFirst().latest().reviewPolicyVersion=null;
            return stored;
        });
        Artifact outline=current.artifacts.getFirst();
        assertThatThrownBy(()->workflow.confirm(current.id,outline.id,outline.latest().id,current.revision,null))
                .hasMessageContaining("执行检查");
        assertThatCode(()->run(current.id,Action.REVIEW,outline.id)).doesNotThrowAnyException();
        Novel rechecked=repository.get(current.id);
        assertThat(rules.reviewCurrent(rechecked,rechecked.artifacts.getFirst().latest())).isTrue();
    }

    @Test void styleReviewCanInspectAnApprovedChapterWithoutChangingRevisionOrConfirmation() {
        Novel n=create();
        generateApprove(n.id,Action.OUTLINE);generateApprove(n.id,Action.CHARACTERS);
        generateApprove(n.id,Action.PLAN);generateApprove(n.id,Action.CHAPTER);
        Novel before=repository.get(n.id);
        Artifact chapter=before.artifacts.stream().filter(a->a.kind==Kind.CHAPTER).findFirst().orElseThrow();
        long revision=before.revision;
        String approvedVersion=chapter.approvedVersionId;
        Review consistency=chapter.latest().review;
        repository.update(n.id,stored->{ stored.status="COMPLETED"; return stored; });

        run(n.id,Action.STYLE_REVIEW,chapter.id);

        Novel after=repository.get(n.id);Artifact checked=rules.artifact(after,chapter.id);
        assertThat(after.revision).isEqualTo(revision);
        assertThat(after.status).isEqualTo("COMPLETED");
        assertThat(checked.clean()).isTrue();
        assertThat(checked.approvedVersionId).isEqualTo(approvedVersion);
        assertThat(checked.latest().review).isEqualTo(consistency);
        assertThat(checked.latest().styleReview).isNotNull();
        assertThat(checked.latest().styleReview.passed()).isTrue();
        assertThat(checked.latest().styleReviewPolicyVersion).isEqualTo(StyleReviewPolicy.VERSION);
    }

    @Test void guidedContentFixUsesOnlyBlockingIssuesAndStopsAfterTwoRounds() {
        Novel n=create(); String id=n.id;
        n=repository.update(id,stored->{
            Artifact chapter=new Artifact(); chapter.kind=Kind.CHAPTER; chapter.chapterNumber=1;
            Version version=new Version(); version.title="第一章"; version.content="需要修订的正文"; version.summary="摘要";
            var must=new ReviewIssue("正文第一段","事实冲突","已确认依据相反","只改这一句","必须修正");
            var choice=new ReviewIssue("正文结尾","节奏可调整","属于阅读偏好","由作者决定","作者决定");
            version.review=new Review(false,List.of(must.text(),choice.text()),false,false,false,List.of(must,choice));
            version.reviewRevision=stored.revision; version.reviewPolicyVersion=ReviewPolicy.VERSION;
            chapter.versions.add(version); stored.artifacts.add(chapter); return stored;
        });
        Artifact chapter=n.artifacts.getLast();
        Task task=tasks.submitContentFix(id,chapter.id,Novel.uid(),n.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->!List.of(TaskStatus.QUEUED,TaskStatus.RUNNING)
                .contains(tasks.find(repository.get(id),task.id).status));
        Novel fixed=repository.get(id); Task result=tasks.find(fixed,task.id); Version candidate=rules.artifact(fixed,chapter.id).latest();
        assertThat(result.status).as(result.error).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(result.automationKind).isEqualTo(TaskService.CONTENT_FIX);
        assertThat(result.instructions).contains("事实冲突").doesNotContain("节奏可调整");
        assertThat(result.instructions)
                .contains("已确认的大纲、人物设定和章节规划高于当前候选及检查建议")
                .contains("禁止选择需要修改已确认依据的分支");
        assertThat(candidate.contentFixRound).isEqualTo(1);
        assertThat(candidate.stylePolishRound).isZero();

        Novel capped=repository.update(id,stored->{
            Version latest=rules.artifact(stored,chapter.id).latest(); latest.contentFixRound=2;
            var must=new ReviewIssue("正文","仍有冲突","依据","修改","必须修正");
            latest.review=new Review(false,List.of(must.text()),false,false,false,List.of(must));
            latest.reviewRevision=stored.revision; latest.reviewPolicyVersion=ReviewPolicy.VERSION; return stored;
        });
        assertThatThrownBy(()->tasks.submitContentFix(capped.id,chapter.id,Novel.uid(),capped.revision))
                .hasMessageContaining("两轮上限");
    }

    @Test void guidedStylePolishRunsOnceThenStoresContentAndStyleRechecks() {
        Novel n=create(); String id=n.id;
        n=repository.update(id,stored->{
            Artifact chapter=new Artifact(); chapter.kind=Kind.CHAPTER; chapter.chapterNumber=1;
            Version version=new Version(); version.title="第一章"; version.content="正文。[演示文风问题]"; version.summary="摘要";
            version.review=new Review(true,List.of(),false,false,false,List.of());
            version.reviewRevision=stored.revision; version.reviewPolicyVersion=ReviewPolicy.VERSION;
            var style=new ReviewIssue("当前正文","正文末尾重复解释","原文：“[演示文风问题]”","删除这句重复解释","建议优化");
            version.styleReview=new Review(true,List.of(style.text()),false,false,false,List.of(style));
            version.styleReviewPolicyVersion=StyleReviewPolicy.VERSION;
            chapter.versions.add(version); stored.artifacts.add(chapter); return stored;
        });
        Artifact chapter=n.artifacts.getLast();
        Task task=tasks.submitStylePolish(id,chapter.id,Novel.uid(),n.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->!List.of(TaskStatus.QUEUED,TaskStatus.RUNNING)
                .contains(tasks.find(repository.get(id),task.id).status));
        Novel polished=repository.get(id); Task result=tasks.find(polished,task.id); Version candidate=rules.artifact(polished,chapter.id).latest();
        assertThat(result.status).as(result.error).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(result.automationKind).isEqualTo(TaskService.STYLE_POLISH);
        assertThat(candidate.stylePolishRound).isEqualTo(1);
        assertThat(candidate.content).isEqualTo("正文。");
        assertThat(candidate.source).isEqualTo("SYSTEM");
        assertThat(candidate.review).isNotNull();
        assertThat(candidate.styleReview).isNotNull();
        assertThat(result.agentRunIds).hasSize(3);
        assertThatThrownBy(()->tasks.submitStylePolish(polished.id,chapter.id,Novel.uid(),polished.revision))
                .hasMessageContaining("已经自动优化过一次");
    }

    @Test void remainingStyleAdviceAfterTheSinglePassDoesNotBlockConfirmation() {
        Novel n=create();
        generateApprove(n.id,Action.OUTLINE); generateApprove(n.id,Action.CHARACTERS); generateApprove(n.id,Action.PLAN);
        run(n.id,Action.CHAPTER,null);
        Novel pending=repository.get(n.id); Artifact chapter=rules.pending(pending);
        repository.update(n.id,stored->{
            Version version=rules.artifact(stored,chapter.id).latest();
            ReviewIssue advice=new ReviewIssue("正文环境段落","可以继续精简环境描写",
                    "原文：“港口的灯沿着海岸亮起。”","是否删除由作者决定","作者决定");
            version.styleReview=new Review(true,List.of(advice.text()),false,false,false,List.of(advice));
            version.styleReviewPolicyVersion=StyleReviewPolicy.VERSION;
            version.stylePolishRound=1;
            return stored;
        });

        Novel ready=repository.get(n.id);
        Novel confirmed=workflow.confirm(n.id,chapter.id,rules.artifact(ready,chapter.id).latest().id,ready.revision,null);

        Artifact approved=rules.artifact(confirmed,chapter.id);
        assertThat(approved.clean()).isTrue();
        assertThat(approved.approved().stylePolishRound).isEqualTo(1);
        assertThat(approved.approved().styleReview.issueDetails()).singleElement()
                .extracting(ReviewIssue::severity).isEqualTo("作者决定");
    }

}
