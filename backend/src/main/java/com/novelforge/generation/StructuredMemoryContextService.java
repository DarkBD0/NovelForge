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
