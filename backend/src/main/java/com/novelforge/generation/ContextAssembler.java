package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.canon.CanonService;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.novel.WordCounter;
import com.novelforge.shared.Problem;
import com.novelforge.workflow.WorkflowRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ContextAssembler {
    public record Context(String json, List<String> sourceVersions, int chapterNumber, int batchNumber) {}
    record ChapterBudget(long minimum, long target, long maximum) {}
    private final CanonService canon;
    private final WorkflowRules rules;
    private final WordCounter words;
    private final ObjectMapper mapper;
    private final int maxChars;
    private RetrievalContextService retrievalContexts;
    public ContextAssembler(CanonService canon, WorkflowRules rules, WordCounter words, ObjectMapper mapper,
                            @Value("${novelforge.model.max-context-chars}") int maxChars) {
        this.canon = canon; this.rules = rules; this.words = words; this.mapper = mapper; this.maxChars = maxChars;
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
        root.put("title", n.title); root.put("synopsis", n.synopsis); root.put("requirements", n.requirements);
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
        root.put("stateModel",stateModel);
        if (action == Action.PLAN) {
            Artifact previousPlan=rules.latestPlan(n);
            int nextBatchStartChapter=previousPlan==null ? 1 : previousPlan.approved().plan.endChapter+1;
            root.put("nextBatchStartChapter",nextBatchStartChapter);
            root.put("unwrittenChapterBeforeNewBatch",chapter);
            root.put("planningBoundaryNote","这是提前生成下一批规划：新批次必须从 nextBatchStartChapter 开始；nextChapter 仍属于已确认的当前批次，不能放入新批次，也不能造成重叠或跳章。");
        }
        if (action==Action.PLAN || target!=null&&target.kind==Kind.PLAN) {
            var planningState=new LinkedHashMap<String,Object>();
            planningState.put("mode",action==Action.PLAN?"NEW_BATCH":"HANDOFF_CALIBRATION");
            planningState.put("confirmedThroughChapter",confirmedThrough);
            planningState.put("nextUnwrittenChapter",rules.nextChapter(n));
            planningState.put("occurredFactsSource","仅使用已确认章节和正式档案");
            planningState.put("futurePlanStatus","条件式设计；不得写成已经发生");
            planningState.put("calibrationBoundary",target!=null&&target.kind==Kind.PLAN
                    ?"默认只更新承接说明、条件假设和紧邻下一章的场景入口；章节目的、揭示边界和章末局面保持原已确认主线"
                    :"生成新批次，承接已发生事实，同时服从已确认大纲和当前批次尚未完成的未来设计");
            root.put("planningState",planningState);
        }
        root.put("remainingWordsToTarget", Math.max(0,n.targetWords-words.approvedWords(n)));
        root.put("remainingWordsToApprovedMax", Math.max(0,n.approvedMaxWords-words.approvedWords(n)));
        root.put("canonBeforeChapter", canon.at(n, chapter));
        rules.plans(n).stream().filter(a -> a.approved().plan.startChapter<=chapter && a.approved().plan.endChapter>=chapter)
                .reduce((first,second)->second).ifPresent(a -> {
                    ChapterBeat beat=a.approved().plan.chapters.stream().filter(item -> item.number()==chapter).findFirst().orElse(null);
                    if (beat!=null) {
                        List<String> eventUnits=acceptanceItems(beat.purpose());
                        var currentPlan=new LinkedHashMap<String,Object>();
                        currentPlan.put("batch",a.batchNumber); currentPlan.put("number",beat.number());
                        currentPlan.put("title",beat.title()); currentPlan.put("purpose",beat.purpose());
                        currentPlan.put("acceptanceItems",eventUnits);
                        root.put("currentChapterPlan",currentPlan);

                        ChapterBudget budget=chapterBudget(n,action,target,chapter,beat);
                        var brief=new LinkedHashMap<String,Object>();
                        brief.put("coreChange",beat.purpose());
                        brief.put("sceneBeats",beat.sceneBeats().isEmpty()?eventUnits:beat.sceneBeats());
                        brief.put("minimumScenes",1); brief.put("maximumScenes",4);
                        brief.put("recommendedWords",Map.of("minimum",budget.minimum(),"target",budget.target(),"maximum",budget.maximum(),
                                "binding",false,"note","单章建议区间，不是确认门禁；不得靠重复内容凑字数"));
                        brief.put("revealBoundary",beat.revealBoundary().isBlank()
                                ?"只完成本章规划的主事件，不提前解释后续章节才揭示的机制或结局":beat.revealBoundary());
                        brief.put("endingHook",beat.endingHook().isBlank()
                                ?"在行动结果、人物选择、有效发现或新问题出现后及时收束，不复述本章意义":beat.endingHook());
                        brief.put("styleMode","功能场景型：保留悬疑、人物互动和必要余韵；环境与时间精度必须影响行动、线索或因果，禁止装饰性铺陈和梗概化");
                        root.put("chapterBrief",brief);
                    }
                });
        n.artifacts.stream().filter(a -> a.kind==Kind.CHAPTER && a.clean() && a.chapterNumber<chapter)
                .max(Comparator.comparingInt(a -> a.chapterNumber)).ifPresent(a -> root.put("previousChapterSummary",a.approved().summary));
        List<Object> accepted = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Artifact a : n.artifacts) {
            if (!a.clean() || a == target) continue;
            if (a.kind == Kind.CHAPTER && a.chapterNumber >= chapter) continue;
            if (action == Action.REWRITE && target != null && target.kind == Kind.CHAPTER
                    && a.kind == Kind.PLAN && a.approved().plan.startChapter > chapter) continue;
            if (target != null && target.kind != Kind.CHAPTER && n.artifacts.indexOf(a) > n.artifacts.indexOf(target)) continue;
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
            // Earlier chapters retain summaries and facts; recent chapters retain full prose.
            if (a.kind != Kind.CHAPTER || a.chapterNumber >= chapter - 3) item.put("content", v.content);
            if (a.kind == Kind.PLAN) item.put("plan", v.plan);
            if (a.kind == Kind.OUTLINE && v.outlineSpec!=null) {
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
            revisionTarget.put("title", v.title); revisionTarget.put("content", v.content);
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
