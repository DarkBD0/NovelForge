package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.canon.CanonService;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.novel.WordCounter;
import com.novelforge.outline.OutlineSpec;
import com.novelforge.shared.Problem;
import com.novelforge.workflow.WorkflowRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ContextAssembler {
    private static final int RECENT_EVENT_CANON_CHAPTERS = 6;
    public record Context(String json, List<String> sourceVersions, int chapterNumber, int batchNumber) {}
    record ChapterBudget(long minimum, long target, long maximum) {}
    private final CanonService canon;
    private final WorkflowRules rules;
    private final WordCounter words;
    private final ObjectMapper mapper;
    private final int maxChars;
    private final RequirementAuthority requirementAuthority;
    private final GenreNarrativeProfile genreProfiles;
    private RetrievalContextService retrievalContexts;
    public ContextAssembler(CanonService canon, WorkflowRules rules, WordCounter words, ObjectMapper mapper,
                            @Value("${novelforge.model.max-context-chars}") int maxChars,
                            RequirementAuthority requirementAuthority,
                            GenreNarrativeProfile genreProfiles) {
        this.canon = canon; this.rules = rules; this.words = words; this.mapper = mapper; this.maxChars = maxChars;
        this.requirementAuthority=requirementAuthority; this.genreProfiles=genreProfiles;
    }
    @Autowired void setRetrievalContexts(RetrievalContextService retrievalContexts) {
        this.retrievalContexts=retrievalContexts;
    }
    public Context assemble(Novel n, Action action, String artifactId) {
        return assemble(n,action,artifactId,null);
    }
    public Context assemble(Novel n, Action action, String artifactId, ConversationBrief conversationBrief) {
        Artifact target = artifactId == null ? null : rules.artifact(n, artifactId);
        int chapter = target != null && target.kind == Kind.CHAPTER ? target.chapterNumber : rules.nextChapter(n);
        int batch = target != null && target.kind == Kind.PLAN ? target.batchNumber : rules.plans(n).size() + 1;
        var root = new LinkedHashMap<String, Object>();
        RequirementAuthority.Snapshot requirementSnapshot=requirementAuthority.resolve(n);
        root.put("title", n.title); root.put("synopsis", n.synopsis); root.put("requirements", requirementSnapshot.text());
        root.put("requirementAuthority",Map.of(
                "strategy",requirementSnapshot.authority(),
                "sourceVersionId",requirementSnapshot.sourceVersionId()==null?"":requirementSnapshot.sourceVersionId(),
                "rule","这里只包含当前有效要求；更早大纲版本中的要求已经被已确认版本整体替代，不得恢复旧要求",
                "entries",requirementSnapshot.entries()));
        GenreNarrativeProfile.Profile genreProfile=genreProfiles.resolve(n);
        root.put("genreContract",genreProfile.asContext());
        if (conversationBrief!=null) {
            root.put("conversationBrief",Map.of(
                    "id",conversationBrief.id,
                    "scope",conversationBrief.scope,
                    "baseRevision",conversationBrief.baseRevision,
                    "baseVersionId",conversationBrief.baseVersionId==null?"":conversationBrief.baseVersionId,
                    "acceptedDecisionIds",List.copyOf(conversationBrief.acceptedDecisionIds),
                    "acceptedDecisions",List.copyOf(conversationBrief.acceptedDecisions),
                    "hash",conversationBrief.hash,
                    "authority","AUTHOR_ACCEPTED_INTENT_NOT_CANON"));
        }
        root.put("targetWords", n.targetWords); root.put("approvedMaxWords", n.approvedMaxWords);
        root.put("confirmedWords", words.approvedWords(n)); root.put("nextChapter", chapter); root.put("nextBatch", batch);
        int confirmedThrough=Math.max(0,rules.nextChapter(n)-1);
        var stateModel=new LinkedHashMap<String,Object>();
        stateModel.put("confirmedHistory",Map.of("state","CONFIRMED_OCCURRED","throughChapter",confirmedThrough,
                "rule","只有已确认正文和正式档案代表已经发生的故事事实"));
        stateModel.put("currentCandidate",Map.of("state",target==null?"NONE":"UNCONFIRMED_CANDIDATE",
                "rule","当前候选可以修改或拒绝，在作者确认前不得当作历史事实"));
        stateModel.put("futurePlans",Map.of("state","CONFIRMED_CONDITIONAL_DESIGN",
                "rule","已确认章节规划是未来设计，不是已经发生的事实；正文确认后只校准承接，默认不重写未来主线"));
        stateModel.put("authorityOrder",List.of("已确认正文事实","已确认大纲和人物硬约束","已确认未来规划的主线设计","当前未确认候选","模型预测"));
        stateModel.put("olderEventCoverage","较早章节的事件明细仍保存在正式档案中；本次输入由各章已确认摘要承接，人物、世界规则、时间线和伏笔档案不缩减");
        root.put("stateModel",stateModel);
        if (action == Action.PLAN) {
            Artifact previousPlan=rules.latestPlan(n);
            int nextBatchStartChapter=previousPlan==null ? 1 : previousPlan.approved().plan.endChapter+1;
            root.put("nextBatchStartChapter",nextBatchStartChapter);
            root.put("unwrittenChapterBeforeNewBatch",chapter);
            root.put("planningBoundaryNote","这是提前生成下一批规划：新批次必须从 nextBatchStartChapter 开始；nextChapter 仍属于已确认的当前批次，不能放入新批次，也不能造成重叠或跳章。");
        }
        if (action==Action.PLAN || target!=null&&target.kind==Kind.PLAN) {
            long confirmedWords=words.approvedWords(n);
            int nextUnwritten=rules.nextChapter(n);
            long priorUnwrittenSuggested=rules.plans(n).stream()
                    .filter(a->target==null || !a.id.equals(target.id))
                    .flatMap(a->a.approved().plan.chapters.stream())
                    .filter(beat->beat.number()>=nextUnwritten && beat.targetWords()!=null)
                    .mapToLong(beat->beat.targetWords()).sum();
            root.put("planningWordBudget",Map.of(
                    "confirmedWords",confirmedWords,
                    "priorUnwrittenSuggestedWords",priorUnwrittenSuggested,
                    "availableForThisAndLaterBatches",Math.max(0,n.approvedMaxWords-confirmedWords-priorUnwrittenSuggested),
                    "approvedMaxWords",n.approvedMaxWords,
                    "rule","本批建议字数与此前未写章节预留之和不能超出批准上限；非最终批次还要给结局留篇幅"));
            var planningState=new LinkedHashMap<String,Object>();
            PlanRevisionMode.Mode planMode=PlanRevisionMode.resolve(n,action,target);
            planningState.put("mode",planMode.name());
            planningState.put("confirmedThroughChapter",confirmedThrough);
            planningState.put("nextUnwrittenChapter",rules.nextChapter(n));
            planningState.put("occurredFactsSource","仅使用已确认章节和正式档案");
            planningState.put("futurePlanStatus","条件式设计；不得写成已经发生");
            List<Artifact> recentConfirmedChapters=n.artifacts.stream()
                    .filter(a->a.kind==Kind.CHAPTER && a.clean())
                    .sorted(Comparator.comparingInt((Artifact a)->a.chapterNumber).reversed()).limit(5).toList();
            planningState.put("recentConfirmedChapterWords",recentConfirmedChapters.stream()
                    .sorted(Comparator.comparingInt(a->a.chapterNumber))
                    .map(a->Map.of("chapter",a.chapterNumber,"words",words.count(a.approved().content))).toList());
            planningState.put("recentAverageChapterWords",recentConfirmedChapters.isEmpty()?0:
                    Math.round(recentConfirmedChapters.stream().mapToLong(a->words.count(a.approved().content))
                            .average().orElse(0)));
            planningState.put("calibrationBoundary",switch(planMode) {
                case UPSTREAM_REPLAN -> "已确认大纲或人物设定发生实质变化，旧规划不再作为未来主线约束；允许重排本批章节范围与目的，但要保留对已写章节编号的覆盖，旧正文须另行改写";
                case HANDOFF_CALIBRATION -> "正文细节变化时默认只更新承接说明、条件假设和紧邻下一章的场景入口；章节目的、揭示边界和章末局面保持原已确认主线";
                case NEW_BATCH -> "生成新批次，承接已发生事实，同时服从已确认大纲和当前批次尚未完成的未来设计";
            });
            root.put("planningState",planningState);
            root.put("planComparison",priorPlanComparison(n,target,action));
            Map<String,Object> storyEngine=planStoryEngine(n);
            if(!storyEngine.isEmpty()) root.put("planStoryEngine",storyEngine);
        }
        root.put("remainingWordsToTarget", Math.max(0,n.targetWords-words.approvedWords(n)));
        root.put("remainingWordsToApprovedMax", Math.max(0,n.approvedMaxWords-words.approvedWords(n)));
        root.put("canonBeforeChapter", contextCanon(n,chapter));
        if(action==Action.COMPLETE) {
            root.put("completionGoals",EndingGoalContract.from(n));
            root.put("completionOpenForeshadowLeads",canon.at(n,Integer.MAX_VALUE).stream()
                    .filter(entry->entry.fact()!=null && "FORESHADOW".equals(entry.fact().type())
                            && "OPEN".equals(entry.fact().state()))
                    .map(entry->Map.of("chapter",entry.chapter(),"detail",entry.fact().detail()))
                    .toList());
        }
        if((action==Action.CHAPTER || target!=null && target.kind==Kind.CHAPTER))
            root.put("knownIdentityHints",knownIdentityHints(n,chapter));
        rules.plans(n).stream().filter(a -> a.approved().plan.startChapter<=chapter && a.approved().plan.endChapter>=chapter)
                .reduce((first,second)->second).ifPresent(a -> {
                    ChapterBeat beat=a.approved().plan.chapters.stream().filter(item -> item.number()==chapter).findFirst().orElse(null);
                    if (beat!=null) {
                        List<String> eventUnits=new ArrayList<>(acceptanceItems(beat.purpose()));
                        List<String> resolutionRequirements=new ArrayList<>();
                        List<ResolutionStep> steps=a.approved().plan.resolutionSteps==null?List.of()
                                :a.approved().plan.resolutionSteps;
                        Map<String,EndingGoalContract.Goal> goals=new HashMap<>();
                        EndingGoalContract.from(n).forEach(goal->goals.put(goal.id(),goal));
                        for(ResolutionStep step:steps) if(step!=null && step.chapterNumber()==chapter) {
                            EndingGoalContract.Goal goal=goals.get(step.goalId());
                            String requirement="结局目标“"+(goal==null?step.expectedResult():goal.goal())+"”："
                                    +step.action()+"；依据来源："+step.evidenceSource()+"；必须得到："
                                    +step.expectedResult()+"；不得越过："+step.certaintyBoundary();
                            resolutionRequirements.add(requirement);
                            eventUnits.add(requirement);
                        }
                        var currentPlan=new LinkedHashMap<String,Object>();
                        currentPlan.put("batch",a.batchNumber); currentPlan.put("number",beat.number());
                        currentPlan.put("title",beat.title()); currentPlan.put("purpose",beat.purpose());
                        currentPlan.put("acceptanceItems",eventUnits);
                        if(!resolutionRequirements.isEmpty()) currentPlan.put("resolutionRequirements",resolutionRequirements);
                        root.put("currentChapterPlan",currentPlan);

                        ChapterBudget budget=chapterBudget(n,action,target,chapter,beat);
                        var brief=new LinkedHashMap<String,Object>();
                        brief.put("coreChange",beat.purpose());
                        if(!resolutionRequirements.isEmpty()) brief.put("resolutionRequirements",resolutionRequirements);
                        brief.put("sceneBeats",beat.sceneBeats().isEmpty()?eventUnits:beat.sceneBeats());
                        brief.put("sceneBeatUsage","场景节点提供可选的实现路径；必须保留本章核心变化、明确主事件和揭示边界，但可压缩不可信或无作用的技术细节。不要逐条照抄场景节点或让每次查看都得到新证据。");
                        brief.put("minimumScenes",1); brief.put("maximumScenes",4);
                        brief.put("recommendedWords",Map.of("minimum",budget.minimum(),"target",budget.target(),"maximum",budget.maximum(),
                                "binding",false,"note","单章建议区间，不是确认门禁；不得靠重复内容凑字数"));
                        brief.put("revealBoundary",beat.revealBoundary().isBlank()
                                ?"只完成本章规划的主事件，不提前解释后续章节才揭示的机制或结局":beat.revealBoundary());
                        brief.put("endingHook",beat.endingHook().isBlank()
                                ?"在行动结果、人物选择、有效发现或新问题出现后及时收束，不复述本章意义":beat.endingHook());
                        brief.put("styleMode",genreProfile.chapterStyle());
                        brief.put("recentChapterProgress",recentChapterProgress(n,chapter));
                        brief.put("planEchoResolution","已确认前章若已问过某问题并得到答复，本章不要仅因规划重复同一问答。可直接承接结果，不必展示普通交接；约定也可能延迟、落空或在幕后变化，之后再揭露。保留本章真正新增的行动、阻力和结果，不把规划当作已经发生的事实。");
                        root.put("chapterBrief",brief);
                    }
                });
        n.artifacts.stream().filter(a -> a.kind==Kind.CHAPTER && a.clean() && a.chapterNumber<chapter)
                .max(Comparator.comparingInt(a -> a.chapterNumber)).ifPresent(a -> root.put("previousChapterSummary",a.approved().summary));
        List<Object> accepted = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        if (target!=null && target.kind==Kind.OUTLINE && List.of(Action.REWRITE,Action.REVIEW).contains(action))
            root.put("outlineRevisionBoundary",outlineRevisionBoundary(n,ids));
        for (Artifact a : n.artifacts) {
            if (!a.clean() || a == target) continue;
            if (a.kind == Kind.CHAPTER && a.chapterNumber >= chapter) continue;
            if (action == Action.REWRITE && target != null && target.kind == Kind.CHAPTER
                    && a.kind == Kind.PLAN && a.approved().plan.startChapter > chapter) continue;
            // Rewriting an old plan must see chapters that were confirmed after that plan.
            // Otherwise the writer may retroactively replace occurred history while calibrating future beats.
            boolean occurredAfterPlan=target!=null && target.kind==Kind.PLAN && a.kind==Kind.CHAPTER;
            if (target != null && target.kind != Kind.CHAPTER && n.artifacts.indexOf(a) > n.artifacts.indexOf(target)
                    && !occurredAfterPlan) continue;
            Version v = a.approved();
            var item = new LinkedHashMap<String, Object>();
            item.put("kind", a.kind); item.put("chapter", a.chapterNumber); item.put("batch", a.batchNumber);
            item.put("authorityState","CONFIRMED");
            item.put("temporalState",a.kind==Kind.PLAN?"FUTURE_CONDITIONAL_DESIGN":
                    a.kind==Kind.CHAPTER?"OCCURRED":"DESIGN_CONSTRAINT");
            item.put("usageBoundary",a.kind==Kind.PLAN
                    ?"未来规划只能约束方向和边界，不能作为事件已经发生的证据"
                    :a.kind==Kind.CHAPTER?"已确认正文，可作为已经发生的事实依据":"已确认设计约束");
            item.put("title", v.title); item.put("versionId", v.id); item.put("summary", v.summary);
            // For chapter work, old batches have already happened or been superseded by later
            // batches. Their approved summaries preserve provenance without repeating both the
            // rendered plan and its structured form in every model request.
            boolean chapterWork=action==Action.CHAPTER || target!=null && target.kind==Kind.CHAPTER;
            boolean compactDesign=chapterWork || action==Action.PLAN || target!=null && target.kind==Kind.PLAN;
            boolean activePlan=a.kind==Kind.PLAN && a.approved().plan!=null
                    && a.approved().plan.startChapter<=chapter && a.approved().plan.endChapter>=chapter;
            if (a.kind==Kind.CHAPTER) {
                if (a.chapterNumber>=chapter-3) item.put("content",v.content);
            } else if (!compactDesign || a.kind!=Kind.PLAN) {
                item.put("content",v.content);
            }
            if (a.kind==Kind.PLAN && (!compactDesign || activePlan)) item.put("plan",v.plan);
            // The rendered outline already contains the same rules. The structured form is
            // retained for outline/plan design, but is redundant for writing/reviewing prose.
            if (a.kind==Kind.OUTLINE && v.outlineSpec!=null && !compactDesign) {
                item.put("outlineSpec",v.outlineSpec); item.put("outlineSpecHash",v.outlineSpecHash);
            }
            accepted.add(item); ids.add(v.id);
        }
        root.put("acceptedReferences", accepted);
        if (retrievalContexts!=null && retrievalEligible(action,target)) {
            RetrievalContextService.Result retrieved=retrievalContexts.retrieve(n,chapter,retrievalQuery(root,target));
            if (retrieved!=null) {
                var retrieval=new LinkedHashMap<String,Object>();
                retrieval.put("status",retrieved.status()); retrieval.put("runId",retrieved.runId());
                retrieval.put("query",retrieved.query()); retrieval.put("beforeChapter",chapter);
                retrieval.put("authorityState","CONFIRMED_RETRIEVAL_AID"); retrieval.put("note",retrieved.note());
                retrieval.put("hits",retrieved.hits()); root.put("retrievedConfirmedHistory",retrieval);
                for(String sourceVersionId:retrieved.sourceVersionIds()) if(!ids.contains(sourceVersionId)) ids.add(sourceVersionId);
            }
        }
        if (target != null) {
            Version v=target.latest();
            var revisionTarget=new LinkedHashMap<String,Object>();
            revisionTarget.put("kind", target.kind); revisionTarget.put("chapterNumber", target.chapterNumber);
            revisionTarget.put("batchNumber", target.batchNumber); revisionTarget.put("versionId", v.id);
            revisionTarget.put("authorityState","UNCONFIRMED_CANDIDATE");
            revisionTarget.put("temporalState",target.kind==Kind.PLAN?"FUTURE_CONDITIONAL_DESIGN":
                    target.kind==Kind.CHAPTER?"CANDIDATE_NOT_OCCURRED":"CANDIDATE_DESIGN");
            revisionTarget.put("title", v.title);
            if(target.kind!=Kind.PLAN || v.plan==null) revisionTarget.put("content",v.content);
            revisionTarget.put("summary", v.summary); revisionTarget.put("facts", v.facts); revisionTarget.put("plan", v.plan);
            if (target.kind==Kind.OUTLINE && v.outlineSpec!=null) {
                revisionTarget.put("outlineSpec",v.outlineSpec); revisionTarget.put("outlineSpecHash",v.outlineSpecHash);
            }
            root.put("revisionTarget", revisionTarget); ids.add(v.id);
            var repairReasons=n.changes.stream().filter(c->c.affectedArtifactIds.contains(target.id)).map(c->c.reason).filter(Objects::nonNull).toList();
            if (!repairReasons.isEmpty()) root.put("repairReasons",repairReasons);
        }
        try {
            String json = mapper.writeValueAsString(root);
            if (json.length() > maxChars) throw new Problem(409, "上下文超过配置容量，任务已停止且未截断关键设定；请提高上下文额度或精简已确认摘要");
            return new Context(json, ids, chapter, batch);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    /** A compact view of approved story motives, not a new source of plot facts. */
    private Map<String,Object> planStoryEngine(Novel novel) {
        Artifact outline=novel.artifacts.stream().filter(a->a.kind==Kind.OUTLINE && !a.needsRevision
                && a.approved()!=null && a.approved().outlineSpec!=null)
                .reduce((earlier,later)->later).orElse(null);
        if(outline==null) return Map.of();
        OutlineSpec spec=outline.approved().outlineSpec;
        Map<String,Object> engine=new LinkedHashMap<>();
        engine.put("sourceVersionId",outline.approvedVersionId);
        engine.put("authority","CONFIRMED_DESIGN_FOCUS");
        putNonblank(engine,"readerAttraction",spec.readerAttraction);
        putNonblank(engine,"protagonistGoal",spec.protagonistGoal);
        putNonblank(engine,"coreConflict",spec.coreConflict);
        putNonblank(engine,"endingContract",spec.endingContract);
        List<EndingGoalContract.Goal> goals=EndingGoalContract.from(novel);
        if(!goals.isEmpty()) engine.put("endingGoals",goals);
        if(spec.characters!=null) {
            List<Map<String,String>> arcs=spec.characters.stream().filter(Objects::nonNull)
                    .filter(character->"PROTAGONIST".equals(character.roleTier)
                            || "CORE_SUPPORT".equals(character.roleTier))
                    .limit(4).map(character->{
                        Map<String,String> arc=new LinkedHashMap<>();
                        putNonblank(arc,"name",character.name);
                        putNonblank(arc,"goal",character.goal);
                        putNonblank(arc,"motivation",character.motivation);
                        putNonblank(arc,"flaw",character.flaw);
                        putNonblank(arc,"keyChoice",character.keyChoice);
                        putNonblank(arc,"arcDirection",character.arcDirection);
                        return arc;
                    }).filter(arc->!arc.isEmpty()).toList();
            if(!arcs.isEmpty()) engine.put("characterArcs",arcs);
        }
        return engine;
    }

    private void putNonblank(Map<String,? super String> target,String key,String value) {
        if(value!=null && !value.isBlank()) target.put(key,value);
    }

    /** Foregrounds actual recent endings so the writer can avoid replaying the same scene rhythm. */
    private List<CanonService.Entry> contextCanon(Novel novel,int beforeChapter) {
        // All facts remain in formal storage. Older one-off events are represented by the
        // approved chapter summaries already included below; durable constraints and open
        // foreshadowing stay available regardless of age.
        int oldestRecentChapter=Math.max(1,beforeChapter-RECENT_EVENT_CANON_CHAPTERS);
        return canon.at(novel,beforeChapter).stream()
                .filter(entry->entry.fact()==null || !"EVENT".equalsIgnoreCase(entry.fact().type())
                        || entry.chapter()>=oldestRecentChapter)
                .toList();
    }

    /** A small, conditional-design window for comparing a new plan with the previous batch. */
    private Map<String,Object> priorPlanComparison(Novel novel,Artifact target,Action action) {
        int start=1;
        if(action==Action.PLAN) {
            start=novel.artifacts.stream().filter(a->a.kind==Kind.PLAN && a.clean()
                            && a.approved().plan!=null)
                    .mapToInt(a->a.approved().plan.endChapter).max().orElse(0)+1;
        } else if(target!=null && target.kind==Kind.PLAN) {
            Version baseline=target.approved()!=null?target.approved():target.latest();
            if(baseline!=null && baseline.plan!=null) start=baseline.plan.startChapter;
        }
        TreeMap<Integer,Map<String,Object>> byChapter=new TreeMap<>();
        for(Artifact artifact:novel.artifacts) {
            if(artifact==target || artifact.kind!=Kind.PLAN || !artifact.clean()) continue;
            Version source=artifact.approved();
            if(source.plan==null || source.plan.chapters==null) continue;
            for(ChapterBeat beat:source.plan.chapters) {
                if(beat==null || beat.number()>=start) continue;
                byChapter.put(beat.number(),Map.of("chapter",beat.number(),"batch",artifact.batchNumber,
                        "purpose",Objects.toString(beat.purpose(),""),
                        "endingHook",Objects.toString(beat.endingHook(),""),
                        "sourceVersionId",source.id));
            }
        }
        List<Map<String,Object>> ordered=new ArrayList<>(byChapter.values());
        List<Map<String,Object>> tail=ordered.subList(Math.max(0,ordered.size()-5),ordered.size());
        return Map.of("comparisonStartChapter",start,"previousBeats",List.copyOf(tail),
                "authorityState","CONFIRMED_FUTURE_CONDITIONAL_DESIGN",
                "usageBoundary","只用于比较规划节奏；旧规划不是已发生剧情，实际已确认正文优先");
    }

    private List<Map<String,Object>> recentChapterProgress(Novel novel,int beforeChapter) {
        List<Artifact> recent=novel.artifacts.stream()
                .filter(a->a.kind==Kind.CHAPTER&&a.clean()&&a.chapterNumber<beforeChapter)
                .sorted(Comparator.comparingInt((Artifact a)->a.chapterNumber).reversed())
                .limit(3).sorted(Comparator.comparingInt(a->a.chapterNumber)).toList();
        List<Map<String,Object>> progress=new ArrayList<>();
        for(Artifact artifact:recent) {
            Version version=artifact.approved();
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("chapter",artifact.chapterNumber);
            item.put("title",version.title);
            item.put("actualOutcome",shortExcerpt(version.summary,180,false));
            item.put("endingExcerpt",shortExcerpt(version.content,140,true));
            item.put("commitmentCues",commitmentCues(version.content));
            progress.add(item);
        }
        return progress;
    }

    /** Surface possible promises buried before a chapter ending; cues are not authoritative facts on their own. */
    private List<String> commitmentCues(String content) {
        if (content==null || content.isBlank()) return List.of();
        List<String> cues=new ArrayList<>();
        for (String sentence:content.split("(?<=[。！？!?])|[\\r\\n]+")) {
            String value=sentence.strip();
            if (value.isEmpty() || value.length()>180) continue;
            if (!value.matches("(?s).*(?:明天|后天|下次|改天|过两天|到时候|等我|稍后|我会|会把).*")) continue;
            cues.add(value);
        }
        return cues.size()<=4?cues:List.copyOf(cues.subList(cues.size()-4,cues.size()));
    }

    private String shortExcerpt(String text,int max,boolean tail) {
        if(text==null) return "";
        String compact=text.strip().replaceAll("\\s+"," ");
        if(compact.length()<=max) return compact;
        return tail?compact.substring(compact.length()-max):compact.substring(0,max);
    }

    private Map<String,Object> knownIdentityHints(Novel novel,int beforeChapter) {
        LinkedHashMap<String,Map<String,Object>> entities=new LinkedHashMap<>();
        LinkedHashMap<String,Map<String,Object>> relations=new LinkedHashMap<>();
        novel.artifacts.stream().filter(a->a.clean() && (a.kind==Kind.CHARACTERS
                || a.kind==Kind.CHAPTER && a.chapterNumber<beforeChapter))
                .sorted(Comparator.comparingInt(a->a.chapterNumber)).forEach(a->{
                    Version version=a.approved();
                    if(version.stateEntities!=null) for(StateEntity entity:version.stateEntities) {
                        entities.remove(entity.key());
                        entities.put(entity.key(),Map.of("key",entity.key(),"type",entity.type(),
                                "name",entity.name(),"aliases",entity.aliases()));
                    }
                    if(version.stateRelations!=null) for(StateRelation relation:version.stateRelations) {
                        relations.remove(relation.key());
                        relations.put(relation.key(),Map.of("key",relation.key(),"fromEntityKey",relation.fromEntityKey(),
                                "type",relation.type(),"toEntityKey",relation.toEntityKey()));
                    }
                });
        return Map.of("authority","CONFIRMED_IDENTITY_CATALOG_NOT_CHAPTER_EVIDENCE",
                "entities",entities.values().stream().skip(Math.max(0,entities.size()-100)).toList(),
                "relations",relations.values().stream().skip(Math.max(0,relations.size()-100)).toList());
    }

    private Map<String,Object> outlineRevisionBoundary(Novel novel,List<String> sourceIds) {
        List<Artifact> chapters=novel.artifacts.stream().filter(a->a.kind==Kind.CHAPTER && a.clean())
                .sorted(Comparator.comparingInt(a->a.chapterNumber)).toList();
        LinkedHashMap<Integer,Artifact> selected=new LinkedHashMap<>();
        chapters.stream().limit(3).forEach(a->selected.put(a.chapterNumber,a));
        chapters.subList(Math.max(0,chapters.size()-3),chapters.size())
                .forEach(a->selected.put(a.chapterNumber,a));
        List<Map<String,Object>> occurred=new ArrayList<>();
        for(Artifact chapter:selected.values()) {
            Version version=chapter.approved(); sourceIds.add(version.id);
            occurred.add(Map.of("chapter",chapter.chapterNumber,"title",version.title,
                    "summary",version.summary,"versionId",version.id,"state","CONFIRMED_OCCURRED"));
        }
        int confirmedThrough=chapters.isEmpty()?0:chapters.getLast().chapterNumber;
        LinkedHashMap<Integer,ChapterBeat> planned=new LinkedHashMap<>();
        for(Artifact plan:novel.artifacts.stream().filter(a->a.kind==Kind.PLAN && a.clean())
                .sorted(Comparator.comparingInt(a->a.batchNumber)).toList()) {
            sourceIds.add(plan.approved().id);
            for(ChapterBeat beat:plan.approved().plan.chapters) {
                if(beat.number()<=5 || beat.number()>confirmedThrough && planned.values().stream()
                        .filter(item->item.number()>confirmedThrough).count()<3)
                    planned.putIfAbsent(beat.number(),beat);
            }
        }
        List<Map<String,Object>> future=planned.values().stream().map(beat->Map.<String,Object>of(
                "chapter",beat.number(),"title",beat.title(),"purpose",beat.purpose(),
                "state",beat.number()<=confirmedThrough?"SUPERSEDED_PLAN_FOR_OCCURRED_CHAPTER":"FUTURE_CONDITIONAL_DESIGN"))
                .toList();
        return Map.of("confirmedThroughChapter",confirmedThrough,"confirmedChapterSummaries",occurred,
                "plannedChapterBeats",future,"approvalEffect",
                "当前大纲只是待确认的修订提案。已发生章节不会被悄悄改写；若作者确认新大纲，与其相依的下游设定、规划或正文会由系统标记为待修订。生成时可提出新的开篇走向，但不得声称旧正文已经发生新事件。整体重写时，检查者不应把旧章节差异作为候选问题重复输出；下游修订范围由系统依赖链单独呈现。" );
    }

    private static boolean retrievalEligible(Action action,Artifact target) {
        if(action==Action.CHAPTER) return true;
        return target!=null && target.kind==Kind.CHAPTER && List.of(Action.REWRITE,Action.REVIEW).contains(action);
    }

    private static String retrievalQuery(Map<String,Object> root,Artifact target) {
        List<String> parts=new ArrayList<>();
        Object plan=root.get("currentChapterPlan");
        if(plan instanceof Map<?,?> map) {
            add(parts,map.get("title")); add(parts,map.get("purpose"));
        }
        if(target!=null && target.latest()!=null) {
            add(parts,target.latest().title); add(parts,target.latest().summary);
        }
        add(parts,root.get("previousChapterSummary"));
        return String.join("。",new LinkedHashSet<>(parts));
    }

    private static void add(List<String> parts,Object value) {
        if(value!=null && !value.toString().isBlank()) parts.add(value.toString().strip());
    }

    private ChapterBudget chapterBudget(Novel n,Action action,Artifact target,int chapter,ChapterBeat beat) {
        long confirmed=words.approvedWords(n);
        long remainingMax=Math.max(0,n.approvedMaxWords-confirmed);
        long center;
        if (action==Action.REWRITE && target!=null && target.kind==Kind.CHAPTER && target.latest()!=null) {
            center=Math.max(1,words.count(target.latest().content));
        } else if (beat.targetWords()!=null && beat.targetWords()>0) {
            center=beat.targetWords();
        } else {
            List<Artifact> recent=n.artifacts.stream().filter(a->a.kind==Kind.CHAPTER && a.clean() && a.chapterNumber<chapter)
                    .sorted(Comparator.comparingInt((Artifact a)->a.chapterNumber).reversed()).limit(5).toList();
            long recentAverage=recent.isEmpty()?0:Math.round(recent.stream().mapToLong(a->words.count(a.approved().content)).average().orElse(0));
            OptionalInt finalChapter=rules.plans(n).stream().map(Artifact::approved).filter(Objects::nonNull)
                    .map(v->v.plan).filter(p->p.finalBatch && p.endChapter>=chapter).mapToInt(p->p.endChapter).max();
            long remainingTarget=Math.max(0,n.targetWords+1-confirmed);
            if (finalChapter.isPresent() && remainingTarget>0) {
                int chaptersLeft=Math.max(1,finalChapter.getAsInt()-chapter+1);
                center=(remainingTarget+chaptersLeft-1)/chaptersLeft;
            } else if (recentAverage>0) center=recentAverage;
            else center=Math.max(300,Math.min(2500,n.targetWords/40));
        }
        center=Math.max(100,Math.min(10000,center));
        long minimum=Math.max(100,Math.round(center*0.85));
        long maximum=Math.max(minimum,Math.round(center*1.15));
        if (remainingMax>0 && action!=Action.REWRITE) maximum=Math.max(1,Math.min(maximum,remainingMax));
        else maximum=Math.max(100,maximum);
        center=Math.min(center,maximum); minimum=Math.min(minimum,center);
        return new ChapterBudget(minimum,center,maximum);
    }

    static List<String> acceptanceItems(String purpose) {
        if (purpose==null) return List.of();
        List<String> parts=new ArrayList<>(); StringBuilder part=new StringBuilder();
        int quotes=0;
        for (int i=0;i<purpose.length();i++) {
            char c=purpose.charAt(i);
            if (c=='‘'||c=='“'||c=='\"') quotes++;
            // Keep commas and colons inside one event unit. Splitting them made the
            // writer turn a natural scene into many separate proof sentences.
            boolean separator=quotes%2==0 && "。；;\n\r".indexOf(c)>=0;
            if (separator) { if (!part.toString().isBlank()) parts.add(part.toString().strip()); part.setLength(0); }
            else part.append(c);
            if (c=='’'||c=='”'||c=='\"') quotes++;
        }
        if (!part.toString().isBlank()) parts.add(part.toString().strip());
        return parts.stream().filter(item -> !item.isBlank())
                .filter(item -> !item.matches("约?\\d+字"))
                .limit(12).toList();
    }
}
