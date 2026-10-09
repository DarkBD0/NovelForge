package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.canon.CanonService;
import com.novelforge.novel.Novel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds a relevance-ranked, character-budgeted role memory from MySQL-authoritative structured memory. */
@Service
public class StructuredMemoryContextService {
    public static final String SELECTION_POLICY_VERSION="structured-memory-selection-v2";
    public record EntityRef(String key,String type,String name,List<String> aliases,String description,
                            String sourceVersionId,int validFromChapter,String status) {}
    public record RelationRef(String key,String fromEntityKey,String type,String toEntityKey,String detail,
                              String sourceVersionId,int validFromChapter,Integer validToChapter,String status) {}
    public record Result(String authorityState,int beforeChapter,List<EntityRef> entities,List<RelationRef> relations,
                         List<String> sourceVersionIds,int usedChars,int maxChars,String selectionPolicyVersion,
                         String note) {
        public Result(String authorityState,int beforeChapter,List<EntityRef> entities,List<RelationRef> relations,
                      List<String> sourceVersionIds,String note) {
            this(authorityState,beforeChapter,entities,relations,sourceVersionIds,0,Integer.MAX_VALUE,
                    SELECTION_POLICY_VERSION,note);
        }
        public boolean empty() { return entities.isEmpty()&&relations.isEmpty(); }
    }
    private record RankedEntity(EntityRef value,int score) {}
    private record RankedRelation(RelationRef value,int score) {}
    private record HistoricalRank(String kind,String key,int score,int chapter,Object value) {}
    private record EvidenceRank(int score,HistoricalStructuredMemoryShadowService.ShadowEvidence evidence) {}

    private final CanonService canon;
    private final ObjectMapper mapper;
    private final int maxChars;
    private final int maxEntities;
    private final int maxRelations;

    public StructuredMemoryContextService(CanonService canon) {
        this(canon,new ObjectMapper(),6000,40,80);
    }

    @Autowired
    public StructuredMemoryContextService(CanonService canon,ObjectMapper mapper,
            @Value("${novelforge.structured-memory.max-context-chars:6000}") int maxChars,
            @Value("${novelforge.structured-memory.max-entities:40}") int maxEntities,
            @Value("${novelforge.structured-memory.max-relations:80}") int maxRelations) {
        if(maxChars<500||maxEntities<1||maxRelations<0) throw new IllegalArgumentException("结构化记忆额度配置不合法");
        this.canon=canon; this.mapper=mapper; this.maxChars=maxChars;
        this.maxEntities=maxEntities; this.maxRelations=maxRelations;
    }

    public Result beforeChapter(Novel novel,int beforeChapter) {
        return beforeChapter(novel,beforeChapter,"");
    }

    public Result beforeChapter(Novel novel,int beforeChapter,String query) {
        var memory=canon.structured(novel);
        List<EntityRef> eligibleEntities=memory.entities().stream()
                .filter(item->item.validFromChapter()<beforeChapter)
                .filter(item->"CURRENT".equals(item.status()))
                .map(item->new EntityRef(item.key(),item.type(),item.name(),item.aliases(),item.description(),
                        item.sourceVersionId(),item.validFromChapter(),item.status()))
                .toList();
        Map<String,EntityRef> byKey=new LinkedHashMap<>();
        eligibleEntities.forEach(item->byKey.put(item.key(),item));
        String normalizedQuery=normalize(query);
        Map<String,Integer> entityScores=new LinkedHashMap<>();
        for(EntityRef entity:eligibleEntities) entityScores.put(entity.key(),entityScore(entity,normalizedQuery));
        List<RankedRelation> rankedRelations=memory.relations().stream()
                .filter(item->item.validFromChapter()<beforeChapter)
                .filter(item->byKey.containsKey(item.fromEntityKey())&&byKey.containsKey(item.toEntityKey()))
                .map(item->new RelationRef(item.key(),item.fromEntityKey(),item.type(),item.toEntityKey(),item.detail(),
                        item.sourceVersionId(),item.validFromChapter(),item.validToChapter(),item.status()))
                .map(item->new RankedRelation(item,entityScores.getOrDefault(item.fromEntityKey(),0)
                        +entityScores.getOrDefault(item.toEntityKey(),0)+textScore(normalizedQuery,item.detail(),300)))
                .sorted(Comparator.comparingInt(RankedRelation::score).reversed()
                        .thenComparing(Comparator.comparingInt((RankedRelation item)->item.value().validFromChapter()).reversed())
                        .thenComparing(item->item.value().key()))
                .toList();
        List<RankedEntity> rankedEntities=eligibleEntities.stream()
                .map(item->new RankedEntity(item,entityScores.getOrDefault(item.key(),0)))
                .sorted(Comparator.comparingInt(RankedEntity::score).reversed()
                        .thenComparing(Comparator.comparingInt((RankedEntity item)->item.value().validFromChapter()).reversed())
                        .thenComparing(item->item.value().key()))
                .toList();

        LinkedHashMap<String,EntityRef> selectedEntities=new LinkedHashMap<>();
        List<RelationRef> selectedRelations=new ArrayList<>();
        for(RankedRelation ranked:rankedRelations) {
            if(selectedRelations.size()>=maxRelations) break;
            RelationRef relation=ranked.value();
            LinkedHashMap<String,EntityRef> candidateEntities=new LinkedHashMap<>(selectedEntities);
            candidateEntities.putIfAbsent(relation.fromEntityKey(),byKey.get(relation.fromEntityKey()));
            candidateEntities.putIfAbsent(relation.toEntityKey(),byKey.get(relation.toEntityKey()));
            if(candidateEntities.size()>maxEntities) continue;
            List<RelationRef> candidateRelations=new ArrayList<>(selectedRelations); candidateRelations.add(relation);
            if(fits(beforeChapter,candidateEntities.values().stream().toList(),candidateRelations)) {
                selectedEntities=candidateEntities; selectedRelations=candidateRelations;
            }
        }
        for(RankedEntity ranked:rankedEntities) {
            if(selectedEntities.size()>=maxEntities) break;
            if(selectedEntities.containsKey(ranked.value().key())) continue;
            LinkedHashMap<String,EntityRef> candidateEntities=new LinkedHashMap<>(selectedEntities);
            candidateEntities.put(ranked.value().key(),ranked.value());
            if(fits(beforeChapter,candidateEntities.values().stream().toList(),selectedRelations)) selectedEntities=candidateEntities;
        }
        List<EntityRef> entities=List.copyOf(selectedEntities.values());
        List<RelationRef> relations=List.copyOf(selectedRelations);
        List<String> sources=sources(entities,relations);
        String note="实体和关系只来自作者已确认版本；已按当前任务相关性和字符额度筛选；ACTIVE 表示当前有效，ENDED 表示已经结束";
        Result result=new Result("CONFIRMED_MYSQL_STRUCTURED_MEMORY",beforeChapter,entities,relations,sources,
                0,maxChars,SELECTION_POLICY_VERSION,note);
        for(int attempt=0;attempt<3;attempt++) {
            int actual=serializedChars(result);
            if(actual==result.usedChars()) break;
            result=new Result(result.authorityState(),beforeChapter,entities,relations,sources,actual,maxChars,
                    SELECTION_POLICY_VERSION,note);
        }
        return result;
    }

    /**
     * Historical shadow extraction can produce far more verified material than one checker should read.
     * Select only candidate-relevant items while preserving exact evidence quotes and relation endpoints.
     */
    public HistoricalStructuredMemoryShadowService.Aggregate selectHistorical(
            HistoricalStructuredMemoryShadowService.Aggregate source,String query) {
        return selectHistorical(source,query,maxChars);
    }

    public HistoricalStructuredMemoryShadowService.Aggregate selectHistorical(
            HistoricalStructuredMemoryShadowService.Aggregate source,String query,int requestedMaxChars) {
        int historicalMaxChars=Math.max(800,Math.min(maxChars,requestedMaxChars));
        if(source==null) return new HistoricalStructuredMemoryShadowService.Aggregate(
                "MODEL_DERIVED_HISTORICAL_SHADOW",List.of(),List.of(),List.of(),"没有历史影子记忆");
        String normalizedQuery=normalize(query);
        Map<String,HistoricalStructuredMemoryShadowService.ShadowEntity> entityByKey=new LinkedHashMap<>();
        source.entities().forEach(item->entityByKey.put(item.key(),item));
        List<HistoricalRank> ranked=new ArrayList<>();
        source.facts().forEach(item->ranked.add(new HistoricalRank("FACT",item.key(),
                historicalScore(normalizedQuery,item.key(),item.type(),item.detail(),
                        item.evidence().stream().map(HistoricalStructuredMemoryShadowService.ShadowEvidence::quote).toList()),
                item.lastChapter(),item)));
        source.entities().forEach(item->{
            List<String> values=new ArrayList<>(); values.add(item.name()); values.add(item.description());
            values.addAll(item.aliases());
            values.addAll(item.evidence().stream().map(HistoricalStructuredMemoryShadowService.ShadowEvidence::quote).toList());
            ranked.add(new HistoricalRank("ENTITY",item.key(),historicalScore(normalizedQuery,item.key(),item.type(),
                    item.name(),values),item.lastChapter(),item));
        });
        source.relations().forEach(item->{
            List<String> values=new ArrayList<>(); values.add(item.fromEntityKey()); values.add(item.toEntityKey());
            values.addAll(item.evidence().stream().map(HistoricalStructuredMemoryShadowService.ShadowEvidence::quote).toList());
            var from=entityByKey.get(item.fromEntityKey()); var to=entityByKey.get(item.toEntityKey());
            if(from!=null) { values.add(from.name()); values.addAll(from.aliases()); }
            if(to!=null) { values.add(to.name()); values.addAll(to.aliases()); }
            ranked.add(new HistoricalRank("RELATION",item.key(),historicalScore(normalizedQuery,item.key(),item.type(),
                    item.detail(),values),item.validFromChapter(),item));
        });
        ranked.sort(Comparator.comparingInt(HistoricalRank::score).reversed()
                .thenComparing(Comparator.comparingInt(HistoricalRank::chapter).reversed())
                .thenComparing(HistoricalRank::kind).thenComparing(HistoricalRank::key));

        List<HistoricalStructuredMemoryShadowService.ShadowFact> facts=new ArrayList<>();
        LinkedHashMap<String,HistoricalStructuredMemoryShadowService.ShadowEntity> entities=new LinkedHashMap<>();
        List<HistoricalStructuredMemoryShadowService.ShadowRelation> relations=new ArrayList<>();
        for(HistoricalRank item:ranked) {
            var candidateFacts=new ArrayList<>(facts);
            var candidateEntities=new LinkedHashMap<>(entities);
            var candidateRelations=new ArrayList<>(relations);
            switch(item.kind()) {
                case "FACT" -> { if(candidateFacts.size()>=80) continue;
                    candidateFacts.add((HistoricalStructuredMemoryShadowService.ShadowFact)item.value()); }
                case "ENTITY" -> { if(candidateEntities.size()>=maxEntities) continue;
                    var value=(HistoricalStructuredMemoryShadowService.ShadowEntity)item.value();
                    candidateEntities.putIfAbsent(value.key(),value); }
                case "RELATION" -> {
                    if(candidateRelations.size()>=maxRelations) continue;
                    var value=(HistoricalStructuredMemoryShadowService.ShadowRelation)item.value();
                    if(entityByKey.containsKey(value.fromEntityKey()))
                        candidateEntities.putIfAbsent(value.fromEntityKey(),entityByKey.get(value.fromEntityKey()));
                    if(entityByKey.containsKey(value.toEntityKey()))
                        candidateEntities.putIfAbsent(value.toEntityKey(),entityByKey.get(value.toEntityKey()));
                    if(candidateEntities.size()>maxEntities) continue;
                    candidateRelations.add(value);
                }
                default -> throw new IllegalStateException("未知历史记忆类型");
            }
            var candidate=historicalAggregate(source,candidateFacts,List.copyOf(candidateEntities.values()),candidateRelations);
            if(serializedChars(candidate)<=historicalMaxChars) {
                facts=candidateFacts; entities=candidateEntities; relations=candidateRelations;
            }
        }
        return historicalAggregate(source,List.copyOf(facts),List.copyOf(entities.values()),List.copyOf(relations),
                historicalMaxChars);
    }

    /** A tiny recall-retry view containing only locally verified chapter quotes. */
    public HistoricalStructuredMemoryShadowService.Aggregate focusHistoricalEvidence(
            HistoricalStructuredMemoryShadowService.Aggregate source,String query,int maxQuotes) {
        if(source==null||maxQuotes<1) return new HistoricalStructuredMemoryShadowService.Aggregate(
                "MODEL_DERIVED_HISTORICAL_SHADOW",List.of(),List.of(),List.of(),"没有可用的历史引文");
        String normalizedQuery=normalize(query);
        LinkedHashMap<String,HistoricalStructuredMemoryShadowService.ShadowEvidence> unique=new LinkedHashMap<>();
        source.facts().forEach(item->item.evidence().forEach(value->unique.putIfAbsent(
                value.sourceVersionId()+"\n"+value.quote(),value)));
        source.entities().forEach(item->item.evidence().forEach(value->unique.putIfAbsent(
                value.sourceVersionId()+"\n"+value.quote(),value)));
        source.relations().forEach(item->item.evidence().forEach(value->unique.putIfAbsent(
                value.sourceVersionId()+"\n"+value.quote(),value)));
        List<EvidenceRank> ranked=unique.values().stream().map(value->new EvidenceRank(
                        textScore(normalizedQuery,value.quote(),2000),value))
                .sorted(Comparator.comparingInt(EvidenceRank::score).reversed()
                        .thenComparing(item->item.evidence().chapterNumber(),Comparator.reverseOrder())
                        .thenComparing(item->item.evidence().quote()))
                .limit(maxQuotes).toList();
        List<HistoricalStructuredMemoryShadowService.ShadowFact> facts=new ArrayList<>();
        int index=0;
        for(EvidenceRank item:ranked) {
            var value=item.evidence();
            facts.add(new HistoricalStructuredMemoryShadowService.ShadowFact("evidence_focus_"+(++index),"EVENT",
                    "候选相关的已确认原文引文，仅用于逐字冲突对照","ACTIVE",value.chapterNumber(),
                    value.chapterNumber(),List.of(value.sourceVersionId()),List.of(value)));
        }
        return new HistoricalStructuredMemoryShadowService.Aggregate(source.authorityState(),List.copyOf(facts),
                List.of(),List.of(),"聚焦复核视图；包装字段不是事实，evidence.quote 是本地逐字验证过的已确认正文");
    }

    private HistoricalStructuredMemoryShadowService.Aggregate historicalAggregate(
            HistoricalStructuredMemoryShadowService.Aggregate source,
            List<HistoricalStructuredMemoryShadowService.ShadowFact> facts,
            List<HistoricalStructuredMemoryShadowService.ShadowEntity> entities,
            List<HistoricalStructuredMemoryShadowService.ShadowRelation> relations) {
        return historicalAggregate(source,facts,entities,relations,maxChars);
    }

    private HistoricalStructuredMemoryShadowService.Aggregate historicalAggregate(
            HistoricalStructuredMemoryShadowService.Aggregate source,
            List<HistoricalStructuredMemoryShadowService.ShadowFact> facts,
            List<HistoricalStructuredMemoryShadowService.ShadowEntity> entities,
            List<HistoricalStructuredMemoryShadowService.ShadowRelation> relations,int characterBudget) {
        return new HistoricalStructuredMemoryShadowService.Aggregate(source.authorityState(),facts,entities,relations,
                source.note()+"；已按当前候选相关性筛选，序列化字符额度不超过 "+characterBudget);
    }

    private int historicalScore(String query,String key,String type,String main,List<String> values) {
        int score=textScore(query,key.replace('_',' '),300)+textScore(query,type,200)+textScore(query,main,600);
        for(String value:values) score+=textScore(query,value,1000);
        return score;
    }

    private boolean fits(int beforeChapter,List<EntityRef> entities,List<RelationRef> relations) {
        Result candidate=new Result("CONFIRMED_MYSQL_STRUCTURED_MEMORY",beforeChapter,entities,relations,
                sources(entities,relations),maxChars,maxChars,SELECTION_POLICY_VERSION,
                "实体和关系只来自作者已确认版本；已按当前任务相关性和字符额度筛选；ACTIVE 表示当前有效，ENDED 表示已经结束");
        return serializedChars(candidate)<=maxChars;
    }

    private int entityScore(EntityRef entity,String query) {
        int score=textScore(query,entity.name(),1000)+textScore(query,entity.key().replace('_',' '),500)
                +textScore(query,entity.description(),200);
        for(String alias:entity.aliases()) score+=textScore(query,alias,800);
        return score;
    }

    private int textScore(String query,String value,int weight) {
        if(query.isBlank()||value==null||value.isBlank()) return 0;
        String normalized=normalize(value);
        if(normalized.length()<2) return 0;
        if(query.contains(normalized)) return weight;
        int overlap=0;
        for(int index=0;index<normalized.length()-1;index++)
            if(query.contains(normalized.substring(index,index+2))) overlap++;
        return Math.min(weight/2,overlap*Math.max(1,weight/20));
    }

    private String normalize(String value) {
        return value==null?"":value.toLowerCase(Locale.ROOT).replaceAll("[\\s，。！？；：、,.!?;:'\"“”‘’（）()\\[\\]【】_-]+","");
    }

    private List<String> sources(List<EntityRef> entities,List<RelationRef> relations) {
        LinkedHashSet<String> values=new LinkedHashSet<>();
        entities.forEach(item->values.add(item.sourceVersionId()));
        relations.forEach(item->values.add(item.sourceVersionId()));
        values.removeIf(value->value==null||value.isBlank());
        return List.copyOf(values);
    }

    private int serializedChars(Object value) {
        try { return mapper.writeValueAsString(value).length(); }
        catch(Exception failure) { throw new IllegalStateException("无法计算结构化记忆字符额度",failure); }
    }
}
