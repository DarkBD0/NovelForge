package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.StateEntity;
import com.novelforge.novel.Novel.StateRelation;
import com.novelforge.novel.Novel.Version;
import com.novelforge.shared.Problem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.novelforge.shared.Problem.require;

/**
 * Re-extracts selected confirmed historical chapters into an ephemeral shadow memory.
 * Nothing produced here is canon, persisted, projected or allowed to advance workflow state.
 */
@Service
public class HistoricalStructuredMemoryShadowService {
    public static final String POLICY_VERSION="historical-structured-memory-shadow-v1";
    public record ShadowEvidence(String quote,String sourceVersionId,int chapterNumber) {}
    public record ShadowFact(String key,String type,String detail,String state,int firstChapter,int lastChapter,
                             List<String> sourceVersionIds,List<ShadowEvidence> evidence) {}
    public record ShadowEntity(String key,String type,String name,List<String> aliases,String description,
                               int firstChapter,int lastChapter,List<String> sourceVersionIds,
                               List<ShadowEvidence> evidence) {}
    public record ShadowRelation(String key,String fromEntityKey,String type,String toEntityKey,String detail,
                                 String state,int validFromChapter,Integer validToChapter,
                                 List<String> sourceVersionIds,List<ShadowEvidence> evidence) {}
    public record Aggregate(String authorityState,List<ShadowFact> facts,List<ShadowEntity> entities,
                            List<ShadowRelation> relations,String note) {}
    public record ChapterResult(int chapterNumber,String versionId,String contentHash,String status,
                                int attempts,long durationMillis,StateExtractionPolicy.ValidatedState extracted,String error) {}
    public record Report(String status,boolean shadowOnly,String scope,String policyVersion,String requestKey,
                         long novelRevision,List<Integer> requestedChapters,int modelCalls,
                         List<ChapterResult> chapters,Aggregate aggregate,String note) {}
    private record Cached(String fingerprint,Report report) {}
    private static final class FactAccumulator {
        String key,type,detail,state; int first,last; final LinkedHashSet<String> sources=new LinkedHashSet<>();
        final LinkedHashMap<String,ShadowEvidence> evidence=new LinkedHashMap<>();
    }
    private static final class EntityAccumulator {
        String key,type,name,description; List<String> aliases=List.of(); int first,last;
        final LinkedHashSet<String> sources=new LinkedHashSet<>();
        final LinkedHashMap<String,ShadowEvidence> evidence=new LinkedHashMap<>();
    }
    private static final class RelationAccumulator {
        String key,from,type,to,detail,state; int first,last; Integer validTo;
        final LinkedHashSet<String> sources=new LinkedHashSet<>();
        final LinkedHashMap<String,ShadowEvidence> evidence=new LinkedHashMap<>();
    }

    private final ModelGateway model;
    private final RoleContextCompiler compiler;
    private final StateExtractionPolicy validations;
    private final ObjectMapper mapper;
    private final SourceSnapshotFactory hashes;
    private final boolean enabled;
    private final int maxChapters;
    private final ConcurrentHashMap<String,Cached> cache=new ConcurrentHashMap<>();

    public HistoricalStructuredMemoryShadowService(ModelGateway model,RoleContextCompiler compiler,
            StateExtractionPolicy validations,ObjectMapper mapper,SourceSnapshotFactory hashes,
            @Value("${novelforge.experiments.historical-structured-memory-shadow-enabled:false}") boolean enabled,
            @Value("${novelforge.experiments.historical-structured-memory-shadow-max-chapters:5}") int maxChapters) {
        if(maxChapters<1||maxChapters>20) throw new IllegalArgumentException("历史结构化记忆影子章节上限必须在 1 到 20 之间");
        this.model=model; this.compiler=compiler; this.validations=validations; this.mapper=mapper;
        this.hashes=hashes; this.enabled=enabled; this.maxChapters=maxChapters;
    }

    public boolean enabled() { return enabled; }

    public Report evaluate(Novel novel,String requestKey,List<Integer> chapterNumbers) {
        require(enabled,"历史结构化记忆影子重建尚未启用");
        require(model.ready(),"真实模型尚未配置，不能执行历史结构化记忆影子重建");
        require(requestKey!=null&&!requestKey.isBlank()&&requestKey.length()<=100,
                "历史影子重建需要不超过 100 字符的幂等键");
        require(chapterNumbers!=null&&!chapterNumbers.isEmpty(),"至少选择一个历史章节");
        require(chapterNumbers.stream().allMatch(number->number!=null&&number>0),"历史章节编号必须是正整数");
        List<Integer> chapters=chapterNumbers.stream().distinct().sorted().toList();
        require(chapters.size()<=maxChapters,"单次历史影子重建最多选择 "+maxChapters+" 个章节");
        Map<Integer,Artifact> selected=selectedChapters(novel,chapters);
        String fingerprint=fingerprint(novel,chapters,selected);
        String key=novel.id+"|"+requestKey;
        Cached existing=cache.get(key);
        if(existing!=null) {
            require(existing.fingerprint().equals(fingerprint),"同一历史影子幂等键不能用于不同章节或小说修订版本");
            return existing.report();
        }
        Cached saved=cache.computeIfAbsent(key,ignored->new Cached(fingerprint,run(novel,requestKey,chapters,selected)));
        require(saved.fingerprint().equals(fingerprint),"同一历史影子幂等键不能用于不同章节或小说修订版本");
        return saved.report();
    }

    private Report run(Novel novel,String requestKey,List<Integer> requested,Map<Integer,Artifact> selected) {
        Map<String,FactAccumulator> facts=new LinkedHashMap<>();
        Map<String,EntityAccumulator> entities=new LinkedHashMap<>();
        Map<String,RelationAccumulator> relations=new LinkedHashMap<>();
        List<ChapterResult> results=new ArrayList<>();
        int calls=0;
        for(int chapter:requested) {
            Artifact artifact=selected.get(chapter); Version version=artifact.approved();
            String contentHash=validations.contentHash(version.content);
            long started=System.nanoTime(); int attempts=0; String error=null;
            StateExtractionPolicy.ValidatedState extracted=null;
            while(attempts<2&&extracted==null) {
                attempts++; calls++;
                try {
                    ContextAssembler.Context base=historicalContext(novel,artifact,aggregate(facts,entities,relations));
                    String instructions="只读重建第 "+chapter+" 章的历史状态；结果是可丢弃影子，不得视为正式档案。";
                    if(error!=null) instructions+=" 上次输出未通过严格校验："+shortError(error)
                            +"。请重新阅读 candidate.content；所有 evidenceQuotes 必须逐字复制正文，关系字段必须完整。";
                    ModelGateway.Request request=new ModelGateway.Request(Novel.Action.CHAPTER,novel,artifact,base,instructions);
                    request=compiler.compileStructuredMemoryShadow(request,AgentRole.STATE_EXTRACTOR);
                    ModelGateway.Generated candidate=new ModelGateway.Generated(version.title,version.content,version.summary,
                            List.of(),null);
                    ModelGateway.StateExtraction proposed=model.extractState(request,candidate);
                    extracted=validations.validateAllBestEffort(candidate,proposed);
                    boolean proposedAny=!proposed.facts().isEmpty()||!proposed.entities().isEmpty()
                            ||!proposed.relations().isEmpty();
                    boolean acceptedAny=!extracted.facts().isEmpty()||!extracted.entities().isEmpty()
                            ||!extracted.relations().isEmpty();
                    if(proposedAny&&!acceptedAny) {
                        extracted=null;
                        throw new Problem(409,"本次提取的所有候选条目都未通过逐条校验");
                    }
                } catch(Exception failure) {
                    error=failure instanceof Problem&&failure.getMessage()!=null?failure.getMessage():"模型调用或证据校验失败";
                }
            }
            if(extracted!=null) {
                merge(chapter,version.id,extracted,facts,entities,relations);
                results.add(new ChapterResult(chapter,version.id,contentHash,"SUCCEEDED",attempts,elapsed(started),extracted,null));
            } else {
                results.add(new ChapterResult(chapter,version.id,contentHash,"FAILED",attempts,elapsed(started),null,error));
            }
        }
        long succeeded=results.stream().filter(item->"SUCCEEDED".equals(item.status())).count();
        String status=succeeded==results.size()?"SUCCEEDED":succeeded==0?"FAILED":"PARTIAL";
        return new Report(status,true,"SELECTED_CONFIRMED_CHAPTERS_READ_ONLY",POLICY_VERSION,requestKey,
                novel.revision,requested,calls,List.copyOf(results),aggregate(facts,entities,relations),
                "结果只存在于当前服务进程的幂等缓存；不会写入正式记忆、投影、小说版本或确认记录");
    }

    private ContextAssembler.Context historicalContext(Novel novel,Artifact current,Aggregate aggregate) throws Exception {
        ObjectNode root=mapper.createObjectNode();
        root.put("title",novel.title); root.put("synopsis",novel.synopsis); root.put("requirements",novel.requirements);
        root.set("stateModel",mapper.valueToTree(Map.of(
                "mode","HISTORICAL_READ_ONLY_SHADOW",
                "authority","MODEL_DERIVED_NOT_CANON",
                "rule","当前章节正文是本章唯一事实证据；此前影子结果只用于稳定编号，不得冒充作者确认内容")));
        root.set("formalStructuredMemory",mapper.valueToTree(aggregate));
        ArrayNode references=root.putArray("acceptedReferences");
        LinkedHashSet<String> sourceVersions=new LinkedHashSet<>();
        for(Artifact artifact:novel.artifacts.stream().filter(Artifact::clean)
                .sorted(Comparator.comparingInt((Artifact item)->item.kind==Kind.CHAPTER?item.chapterNumber:0)).toList()) {
            if(artifact==current) continue;
            // Historical prose and summaries are deliberately excluded: they tempt the extractor to quote
            // context instead of the current candidate. Stable identifiers come from the prior shadow aggregate.
            if(artifact.kind==Kind.CHAPTER) continue;
            if(artifact.kind==Kind.PLAN && (artifact.approved().plan==null
                    || artifact.approved().plan.startChapter>current.chapterNumber
                    || artifact.approved().plan.endChapter<current.chapterNumber)) continue;
            Version version=artifact.approved(); ObjectNode item=references.addObject();
            item.put("kind",artifact.kind.name()); item.put("chapter",artifact.chapterNumber);
            item.put("batch",artifact.batchNumber); item.put("authorityState","CONFIRMED");
            item.put("versionId",version.id); item.put("title",version.title); item.put("summary",version.summary);
            if(artifact.kind!=Kind.CHAPTER) item.put("content",version.content);
            if(artifact.kind==Kind.PLAN) item.set("plan",mapper.valueToTree(version.plan));
            sourceVersions.add(version.id);
        }
        aggregate.facts().forEach(item->sourceVersions.addAll(item.sourceVersionIds()));
        aggregate.entities().forEach(item->sourceVersions.addAll(item.sourceVersionIds()));
        aggregate.relations().forEach(item->sourceVersions.addAll(item.sourceVersionIds()));
        return new ContextAssembler.Context(mapper.writeValueAsString(root),List.copyOf(sourceVersions),
                current.chapterNumber,0);
    }

    private Map<Integer,Artifact> selectedChapters(Novel novel,List<Integer> chapters) {
        Map<Integer,Artifact> available=new LinkedHashMap<>();
        novel.artifacts.stream().filter(item->item.kind==Kind.CHAPTER&&item.clean())
                .forEach(item->available.put(item.chapterNumber,item));
        for(int chapter:chapters) require(available.containsKey(chapter),"第 "+chapter+" 章不是可用的已确认正文");
        Map<Integer,Artifact> result=new LinkedHashMap<>(); chapters.forEach(number->result.put(number,available.get(number)));
        return result;
    }

    private String fingerprint(Novel novel,List<Integer> chapters,Map<Integer,Artifact> selected) {
        StringBuilder value=new StringBuilder().append(novel.revision);
        for(int chapter:chapters) {
            Version version=selected.get(chapter).approved();
            value.append('\n').append(chapter).append(':').append(version.id).append(':')
                    .append(validations.contentHash(version.content));
        }
        return hashes.hash(value.toString());
    }

    private void merge(int chapter,String sourceVersionId,StateExtractionPolicy.ValidatedState extracted,
            Map<String,FactAccumulator> facts,Map<String,EntityAccumulator> entities,
            Map<String,RelationAccumulator> relations) {
        Map<String,List<String>> evidenceByKey=extracted.evidence().stream().collect(java.util.stream.Collectors.toMap(
                Novel.StateEvidence::key,Novel.StateEvidence::evidenceQuotes,(left,right)->left,LinkedHashMap::new));
        extracted.facts().forEach(item->{
            FactAccumulator value=facts.computeIfAbsent(item.key(),ignored->{ var created=new FactAccumulator();
                created.key=item.key(); created.first=chapter; return created; });
            value.type=item.type(); value.detail=item.detail(); value.state=item.state(); value.last=chapter;
            value.sources.add(sourceVersionId);
            addEvidence(value.evidence,evidenceByKey.get(item.key()),sourceVersionId,chapter);
        });
        for(StateEntity item:extracted.entities()) {
            EntityAccumulator value=entities.computeIfAbsent(item.key(),ignored->{ var created=new EntityAccumulator();
                created.key=item.key(); created.first=chapter; return created; });
            value.type=item.type(); value.name=item.name(); value.aliases=item.aliases();
            value.description=item.description(); value.last=chapter; value.sources.add(sourceVersionId);
            addEvidence(value.evidence,evidenceByKey.get(item.key()),sourceVersionId,chapter);
        }
        for(StateRelation item:extracted.relations()) {
            RelationAccumulator value=relations.computeIfAbsent(item.key(),ignored->{ var created=new RelationAccumulator();
                created.key=item.key(); created.first=chapter; return created; });
            value.from=item.fromEntityKey(); value.type=item.type(); value.to=item.toEntityKey();
            value.detail=item.detail(); value.state=item.state(); value.last=chapter;
            value.validTo="ENDED".equals(item.state())?chapter:null; value.sources.add(sourceVersionId);
            addEvidence(value.evidence,evidenceByKey.get(item.key()),sourceVersionId,chapter);
        }
    }

    private void addEvidence(Map<String,ShadowEvidence> target,List<String> quotes,String sourceVersionId,int chapter) {
        if(quotes==null) return;
        for(String quote:quotes) target.putIfAbsent(sourceVersionId+"\n"+quote,
                new ShadowEvidence(quote,sourceVersionId,chapter));
    }

    private Aggregate aggregate(Map<String,FactAccumulator> facts,Map<String,EntityAccumulator> entities,
                                Map<String,RelationAccumulator> relations) {
        List<ShadowFact> factValues=facts.values().stream().map(item->new ShadowFact(item.key,item.type,item.detail,
                item.state,item.first,item.last,List.copyOf(item.sources),List.copyOf(item.evidence.values()))).toList();
        List<ShadowEntity> entityValues=entities.values().stream().map(item->new ShadowEntity(item.key,item.type,
                item.name,item.aliases,item.description,item.first,item.last,List.copyOf(item.sources),
                List.copyOf(item.evidence.values()))).toList();
        List<ShadowRelation> relationValues=relations.values().stream().map(item->new ShadowRelation(item.key,item.from,
                item.type,item.to,item.detail,item.state,item.first,item.validTo,List.copyOf(item.sources),
                List.copyOf(item.evidence.values()))).toList();
        return new Aggregate("MODEL_DERIVED_HISTORICAL_SHADOW",factValues,entityValues,relationValues,
                "description/detail 是模型归纳，不是正式事实；evidence.quote 是从已确认章节正文逐字校验过的只读引文");
    }

    private long elapsed(long started) { return (System.nanoTime()-started)/1_000_000; }
    private String shortError(String value) {
        String normalized=value==null?"未知校验错误":value.replaceAll("\\s+"," ").strip();
        return normalized.length()<=500?normalized:normalized.substring(0,500)+"…";
    }
}
