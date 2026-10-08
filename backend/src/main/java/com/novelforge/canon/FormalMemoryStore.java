package com.novelforge.canon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Fact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.StateEntity;
import com.novelforge.novel.Novel.StateEvidence;
import com.novelforge.novel.Novel.StateRelation;
import com.novelforge.novel.Novel.Version;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Materialized, source-traceable memory derived only from author-confirmed character
 * and chapter versions. It never interprets prose or promotes conversation content.
 */
@Component
public class FormalMemoryStore {
    private static final String CHECKPOINT_PREFIX="FORMAL_MEMORY:";
    public record MemoryEntry(Fact fact,String sourceArtifactId,String sourceVersionId,int chapter) {}
    public record EntityMemory(String id,String key,String type,String name,List<String> aliases,String description,
                               String sourceVersionId,int validFromChapter,String status) {}
    public record RelationMemory(String id,String key,String fromEntityId,String fromEntityKey,String type,
                                 String toEntityId,String toEntityKey,String detail,String sourceVersionId,
                                 int validFromChapter,Integer validToChapter,String status) {}
    public record StructuredMemory(List<EntityMemory> entities,List<RelationMemory> relations) {}
    public record SyncResult(boolean changed,int factRows,int activeFacts,int stateDeltas,int events,int foreshadows,
                             int entities,int relations) {}
    private record DraftFact(String id,String identity,Fact fact,String artifactId,String versionId,int chapter,int order) {}
    private record FactRow(DraftFact source,Integer validTo,String rowStatus) {}
    private record DeltaRow(String versionId,int schemaVersion,String payload,String payloadHash,String status,String createdAt,String decidedAt) {}
    private record EntityDraft(StateEntity entity,String versionId,int firstChapter) {}
    private record EntityRow(String id,StateEntity entity,String versionId,int firstChapter) {}
    private record RelationDraft(StateRelation relation,String versionId,int firstChapter,int latestChapter) {}
    private record RelationRow(String id,StateRelation relation,String fromEntityId,String toEntityId,String versionId,
                               int firstChapter,Integer validToChapter) {}
    private record DeltaPayload(List<Fact> facts,List<StateEntity> entities,List<StateRelation> relations,
                                List<StateEvidence> evidence) {}
    private record Snapshot(List<FactRow> facts,List<DeltaRow> deltas,List<FactRow> events,List<ForeshadowRow> foreshadows,
                            List<EntityRow> entities,List<RelationRow> relations,String hash) {}
    private record ForeshadowRow(FactRow current,int plantedChapter,List<Integer> developedChapters,Integer resolvedChapter) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean enabled;

    public FormalMemoryStore(JdbcTemplate jdbc,ObjectMapper mapper,
                             @Value("${novelforge.storage.mode:legacy}") String storageMode) {
        this.jdbc=jdbc;
        this.mapper=mapper;
        this.enabled="normalized".equalsIgnoreCase(storageMode);
    }

    public boolean enabled() { return enabled; }

    public SyncResult synchronize(Novel novel) {
        if(!enabled) return new SyncResult(false,0,0,0,0,0,0,0);
        Snapshot snapshot=snapshot(novel);
        String checkpoint=checkpoint(novel.id);
        List<String> metadata=jdbc.query("SELECT metadata_json FROM projection_checkpoint WHERE target=?",
                (rs,index)->rs.getString(1),checkpoint);
        if(!metadata.isEmpty() && snapshot.hash.equals(metadataHash(metadata.getFirst()))) {
            return result(false,snapshot);
        }

        jdbc.update("DELETE FROM entity_relation WHERE novel_id=?",novel.id);
        jdbc.update("DELETE FROM canon_entity WHERE novel_id=?",novel.id);
        jdbc.update("DELETE FROM state_delta WHERE novel_id=?",novel.id);
        jdbc.update("DELETE FROM canon_fact WHERE novel_id=?",novel.id);
        jdbc.update("DELETE FROM story_event WHERE novel_id=?",novel.id);
        jdbc.update("DELETE FROM foreshadow WHERE novel_id=?",novel.id);

        for(DeltaRow delta:snapshot.deltas) {
            jdbc.update("INSERT INTO state_delta(id,novel_id,artifact_version_id,status,schema_version,payload_json,payload_hash,created_at,decided_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    delta.versionId,novel.id,delta.versionId,delta.status,delta.schemaVersion,delta.payload,delta.payloadHash,delta.createdAt,delta.decidedAt);
        }
        for(FactRow row:snapshot.facts) {
            DraftFact source=row.source;
            jdbc.update("INSERT INTO canon_fact(id,novel_id,entity_id,fact_type,fact_key,detail,fact_state,source_version_id,valid_from_chapter,valid_to_chapter,status) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    source.id,novel.id,null,source.fact.type(),source.fact.key(),source.fact.detail(),source.fact.state(),
                    source.versionId,source.chapter,row.validTo,row.rowStatus);
        }
        int sequence=0;
        for(FactRow row:snapshot.events) {
            DraftFact source=row.source;
            jdbc.update("INSERT INTO story_event(id,novel_id,event_type,title,summary,chapter_number,sequence_no,time_text,location_entity_id,source_version_id,status) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    source.id,novel.id,source.fact.type(),source.fact.key(),source.fact.detail(),source.chapter,sequence++,null,null,
                    source.versionId,value(source.fact.state(),"ACTIVE"));
        }
        for(ForeshadowRow item:snapshot.foreshadows) {
            DraftFact source=item.current.source;
            jdbc.update("INSERT INTO foreshadow(id,novel_id,title,description,planted_chapter,developed_chapters_json,resolved_chapter,status,source_version_id) VALUES(?,?,?,?,?,?,?,?,?)",
                    source.id,novel.id,source.fact.key(),source.fact.detail(),item.plantedChapter,json(item.developedChapters),
                    item.resolvedChapter,value(source.fact.state(),"OPEN"),source.versionId);
        }
        for(EntityRow row:snapshot.entities) {
            StateEntity entity=row.entity;
            jdbc.update("INSERT INTO canon_entity(id,novel_id,entity_key,entity_type,canonical_name,aliases_json,description,source_version_id,valid_from_chapter,valid_to_chapter,status) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    row.id,novel.id,entity.key(),entity.type(),entity.name(),json(entity.aliases()),entity.description(),
                    row.versionId,row.firstChapter,null,"CURRENT");
        }
        for(RelationRow row:snapshot.relations) {
            StateRelation relation=row.relation;
            jdbc.update("INSERT INTO entity_relation(id,novel_id,relation_key,from_entity_id,relation_type,to_entity_id,detail,source_version_id,valid_from_chapter,valid_to_chapter,status) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    row.id,novel.id,relation.key(),row.fromEntityId,relation.type(),row.toEntityId,relation.detail(),
                    row.versionId,row.firstChapter,row.validToChapter,relation.state());
        }

        Map<String,Object> checkpointData=new LinkedHashMap<>();
        checkpointData.put("memoryHash",snapshot.hash); checkpointData.put("factRows",snapshot.facts.size());
        checkpointData.put("activeFacts",activeCount(snapshot)); checkpointData.put("stateDeltas",snapshot.deltas.size());
        checkpointData.put("events",snapshot.events.size()); checkpointData.put("foreshadows",snapshot.foreshadows.size());
        checkpointData.put("entities",snapshot.entities.size()); checkpointData.put("relations",snapshot.relations.size());
        String now=Instant.now().toString(); String metadataJson=json(checkpointData);
        int updated=jdbc.update("UPDATE projection_checkpoint SET last_event_id=?,updated_at=?,metadata_json=? WHERE target=?",
                null,now,metadataJson,checkpoint);
        if(updated==0) jdbc.update("INSERT INTO projection_checkpoint(target,last_event_id,updated_at,metadata_json) VALUES(?,?,?,?)",
                checkpoint,null,now,metadataJson);
        return result(true,snapshot);
    }

    public Optional<List<MemoryEntry>> at(Novel novel,int beforeChapter) {
        if(!enabled || !hasCheckpoint(novel.id)) return Optional.empty();
        return Optional.of(jdbc.query("""
                SELECT cf.fact_key,cf.fact_type,cf.detail,cf.fact_state,cf.source_version_id,cf.valid_from_chapter,av.artifact_id
                FROM canon_fact cf
                LEFT JOIN artifact_version av ON av.id=cf.source_version_id AND av.novel_id=cf.novel_id
                WHERE cf.novel_id=? AND cf.valid_from_chapter<?
                  AND (cf.valid_to_chapter IS NULL OR cf.valid_to_chapter>=?)
                ORDER BY cf.valid_from_chapter,cf.fact_type,cf.fact_key
                """,(rs,index)->new MemoryEntry(
                new Fact(rs.getString("fact_key"),rs.getString("fact_type"),rs.getString("detail"),rs.getString("fact_state")),
                rs.getString("artifact_id"),rs.getString("source_version_id"),rs.getInt("valid_from_chapter")),
                novel.id,beforeChapter,beforeChapter));
    }

    public Optional<StructuredMemory> structured(String novelId) {
        if(!enabled || !hasCheckpoint(novelId)) return Optional.empty();
        List<EntityMemory> entities=jdbc.query("""
                SELECT id,entity_key,entity_type,canonical_name,aliases_json,description,source_version_id,valid_from_chapter,status
                FROM canon_entity WHERE novel_id=? ORDER BY valid_from_chapter,entity_type,entity_key
                """,(rs,index)->new EntityMemory(rs.getString("id"),rs.getString("entity_key"),rs.getString("entity_type"),
                rs.getString("canonical_name"),stringList(rs.getString("aliases_json")),value(rs.getString("description"),""),
                rs.getString("source_version_id"),rs.getInt("valid_from_chapter"),rs.getString("status")),novelId);
        List<RelationMemory> relations=jdbc.query("""
                SELECT er.id,er.relation_key,er.from_entity_id,source.entity_key AS from_entity_key,
                       er.relation_type,er.to_entity_id,target.entity_key AS to_entity_key,er.detail,
                       er.source_version_id,er.valid_from_chapter,er.valid_to_chapter,er.status
                FROM entity_relation er
                JOIN canon_entity source ON source.id=er.from_entity_id AND source.novel_id=er.novel_id
                JOIN canon_entity target ON target.id=er.to_entity_id AND target.novel_id=er.novel_id
                WHERE er.novel_id=? ORDER BY er.valid_from_chapter,er.relation_type,er.relation_key
                """,(rs,index)->new RelationMemory(rs.getString("id"),rs.getString("relation_key"),
                rs.getString("from_entity_id"),rs.getString("from_entity_key"),rs.getString("relation_type"),
                rs.getString("to_entity_id"),rs.getString("to_entity_key"),value(rs.getString("detail"),""),
                rs.getString("source_version_id"),rs.getInt("valid_from_chapter"),nullableInteger(rs,"valid_to_chapter"),
                rs.getString("status")),novelId);
        return Optional.of(new StructuredMemory(List.copyOf(entities),List.copyOf(relations)));
    }

    public boolean hasCheckpoint(String novelId) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM projection_checkpoint WHERE target=?",Integer.class,checkpoint(novelId));
        return count!=null && count>0;
    }

    private Snapshot snapshot(Novel novel) {
        List<DraftFact> drafts=new ArrayList<>(); int order=0;
        for(Artifact artifact:novel.artifacts) {
            if(!authoritative(artifact) || (artifact.kind!=Kind.CHARACTERS && artifact.kind!=Kind.CHAPTER)) continue;
            Version version=artifact.approved();
            Map<String,DraftFact> uniqueInVersion=new LinkedHashMap<>();
            for(Fact fact:safeFacts(version)) {
                String identity=identity(fact);
                String id=hash(novel.id+"\u0000"+identity+"\u0000"+version.id);
                uniqueInVersion.put(identity,new DraftFact(id,identity,fact,artifact.id,version.id,artifact.chapterNumber,order++));
            }
            drafts.addAll(uniqueInVersion.values());
        }
        Map<String,List<DraftFact>> histories=new LinkedHashMap<>();
        for(DraftFact draft:drafts) histories.computeIfAbsent(draft.identity,key->new ArrayList<>()).add(draft);
        List<FactRow> facts=new ArrayList<>(); List<FactRow> current=new ArrayList<>();
        for(List<DraftFact> history:histories.values()) {
            for(int i=0;i<history.size();i++) {
                DraftFact source=history.get(i);
                Integer validTo=i+1<history.size()?history.get(i+1).chapter:null;
                FactRow row=new FactRow(source,validTo,i+1==history.size()?"CURRENT":"SUPERSEDED");
                facts.add(row); if(i+1==history.size()) current.add(row);
            }
        }
        facts.sort((left,right)->Integer.compare(left.source.order,right.source.order));
        current.sort((left,right)->Integer.compare(left.source.order,right.source.order));

        Map<String,DeltaRow> deltas=new LinkedHashMap<>();
        Map<String,Artifact> artifacts=new LinkedHashMap<>();
        for(Artifact artifact:novel.artifacts) artifacts.put(artifact.id,artifact);
        for(Novel.Approval approval:novel.approvals) {
            Artifact artifact=artifacts.get(approval.artifactId());
            if(artifact==null || (artifact.kind!=Kind.CHARACTERS && artifact.kind!=Kind.CHAPTER)) continue;
            Version version=artifact.versions.stream().filter(item->item.id.equals(approval.versionId())).findFirst().orElse(null);
            if(version==null) continue;
            String payload=json(new DeltaPayload(safeFacts(version),safeEntities(version),safeRelations(version),safeEvidence(version)));
            String status=authoritative(artifact) && version.id.equals(artifact.approvedVersionId)?"APPLIED":"SUPERSEDED";
            deltas.put(version.id,new DeltaRow(version.id,2,payload,hash(payload),status,version.createdAt,approval.time()));
        }
        List<FactRow> events=current.stream().filter(row->List.of("EVENT","TIMELINE").contains(row.source.fact.type())).toList();
        List<ForeshadowRow> foreshadows=new ArrayList<>();
        for(Map.Entry<String,List<DraftFact>> entry:histories.entrySet()) {
            List<DraftFact> history=entry.getValue();
            if(history.isEmpty() || !"FORESHADOW".equals(history.getFirst().fact.type())) continue;
            DraftFact latest=history.getLast();
            FactRow latestRow=current.stream().filter(row->row.source.id.equals(latest.id)).findFirst().orElseThrow();
            List<Integer> chapters=history.stream().map(item->item.chapter).distinct().toList();
            Integer resolved="RESOLVED".equalsIgnoreCase(latest.fact.state())?latest.chapter:null;
            foreshadows.add(new ForeshadowRow(latestRow,history.getFirst().chapter,chapters,resolved));
        }

        Map<String,EntityDraft> entityDrafts=new LinkedHashMap<>();
        Map<String,RelationDraft> relationDrafts=new LinkedHashMap<>();
        for(Artifact artifact:novel.artifacts) {
            if(!authoritative(artifact) || (artifact.kind!=Kind.CHARACTERS && artifact.kind!=Kind.CHAPTER)) continue;
            Version version=artifact.approved();
            for(StateEntity entity:formalEntities(version)) {
                EntityDraft previous=entityDrafts.get(entity.key());
                if(previous!=null && !previous.entity.type().equals(entity.type()))
                    throw new IllegalStateException("正式实体稳定编号类型冲突："+entity.key());
                int firstChapter=previous==null?artifact.chapterNumber:Math.min(previous.firstChapter,artifact.chapterNumber);
                List<String> aliases=mergeAliases(previous==null?null:previous.entity,entity);
                String description=blank(entity.description()) && previous!=null?previous.entity.description():value(entity.description(),"");
                String name=blank(entity.name()) && previous!=null?previous.entity.name():entity.name();
                entityDrafts.put(entity.key(),new EntityDraft(new StateEntity(entity.key(),entity.type(),name,aliases,description),
                        version.id,firstChapter));
            }
            for(StateRelation relation:formalRelations(version)) {
                RelationDraft previous=relationDrafts.get(relation.key());
                if(previous!=null && (!previous.relation.fromEntityKey().equals(relation.fromEntityKey())
                        || !previous.relation.toEntityKey().equals(relation.toEntityKey())
                        || !previous.relation.type().equals(relation.type())))
                    throw new IllegalStateException("正式关系稳定编号语义冲突："+relation.key());
                int firstChapter=previous==null?artifact.chapterNumber:Math.min(previous.firstChapter,artifact.chapterNumber);
                relationDrafts.put(relation.key(),new RelationDraft(relation,version.id,firstChapter,artifact.chapterNumber));
            }
        }
        List<EntityRow> entities=new ArrayList<>();
        for(EntityDraft draft:entityDrafts.values())
            entities.add(new EntityRow(entityId(novel.id,draft.entity.key()),draft.entity,draft.versionId,draft.firstChapter));
        List<RelationRow> relations=new ArrayList<>();
        for(RelationDraft draft:relationDrafts.values()) {
            StateRelation relation=draft.relation;
            if(!entityDrafts.containsKey(relation.fromEntityKey()) || !entityDrafts.containsKey(relation.toEntityKey()))
                throw new IllegalStateException("正式关系引用了不存在的实体："+relation.key());
            Integer validTo="ENDED".equals(relation.state())?draft.latestChapter:null;
            relations.add(new RelationRow(relationId(novel.id,relation.key()),relation,
                    entityId(novel.id,relation.fromEntityKey()),entityId(novel.id,relation.toEntityKey()),
                    draft.versionId,draft.firstChapter,validTo));
        }
        Map<String,Object> hashInput=new LinkedHashMap<>();
        hashInput.put("facts",facts); hashInput.put("deltas",List.copyOf(deltas.values()));
        hashInput.put("events",events); hashInput.put("foreshadows",foreshadows);
        hashInput.put("entities",entities); hashInput.put("relations",relations);
        String hash=hash(json(hashInput));
        return new Snapshot(List.copyOf(facts),List.copyOf(deltas.values()),events,List.copyOf(foreshadows),
                List.copyOf(entities),List.copyOf(relations),hash);
    }

    private List<Fact> safeFacts(Version version) { return version.facts==null?List.of():version.facts; }
    private List<StateEntity> safeEntities(Version version) { return version.stateEntities==null?List.of():version.stateEntities; }
    private List<StateRelation> safeRelations(Version version) { return version.stateRelations==null?List.of():version.stateRelations; }
    private List<StateEvidence> safeEvidence(Version version) { return version.stateEvidence==null?List.of():version.stateEvidence; }
    private List<StateEntity> formalEntities(Version version) {
        return trustedStateExtraction(version)?safeEntities(version):List.of();
    }
    private List<StateRelation> formalRelations(Version version) {
        return trustedStateExtraction(version)?safeRelations(version):List.of();
    }
    private boolean trustedStateExtraction(Version version) {
        return !version.stateExtractionRequired || version.stateExtractionStatus==Novel.StateExtractionStatus.SUCCEEDED;
    }
    private boolean authoritative(Artifact artifact) {
        return artifact!=null && artifact.approvedVersionId!=null && !artifact.needsRevision && artifact.approved()!=null;
    }
    private List<String> mergeAliases(StateEntity previous,StateEntity current) {
        LinkedHashSet<String> values=new LinkedHashSet<>();
        if(previous!=null) {
            values.addAll(previous.aliases());
            if(!previous.name().equals(current.name())) values.add(previous.name());
        }
        values.addAll(current.aliases()); values.remove(current.name()); values.removeIf(this::blank);
        return List.copyOf(values);
    }
    private String entityId(String novelId,String key) { return hash(novelId+"\u0000entity\u0000"+key); }
    private String relationId(String novelId,String key) { return hash(novelId+"\u0000relation\u0000"+key); }
    private String identity(Fact fact) { return value(fact.type(),"")+":"+value(fact.key(),""); }
    private String checkpoint(String novelId) { return CHECKPOINT_PREFIX+novelId; }
    private int activeCount(Snapshot snapshot) { return (int)snapshot.facts.stream().filter(row->"CURRENT".equals(row.rowStatus)).count(); }
    private SyncResult result(boolean changed,Snapshot snapshot) {
        return new SyncResult(changed,snapshot.facts.size(),activeCount(snapshot),snapshot.deltas.size(),snapshot.events.size(),
                snapshot.foreshadows.size(),snapshot.entities.size(),snapshot.relations.size());
    }
    private String metadataHash(String metadata) {
        try { return mapper.readTree(metadata).path("memoryHash").asText(); }
        catch(JsonProcessingException e) { return ""; }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(JsonProcessingException e) { throw new IllegalStateException("无法序列化正式记忆",e); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String value(String value,String fallback) { return value==null?fallback:value; }
    private boolean blank(String value) { return value==null || value.isBlank(); }
    private List<String> stringList(String json) {
        try { return mapper.readValue(json,mapper.getTypeFactory().constructCollectionType(List.class,String.class)); }
        catch(JsonProcessingException e) { throw new IllegalStateException("无法读取正式实体别名",e); }
    }
    private Integer nullableInteger(java.sql.ResultSet rs,String column) throws java.sql.SQLException {
        int value=rs.getInt(column); return rs.wasNull()?null:value;
    }
}
