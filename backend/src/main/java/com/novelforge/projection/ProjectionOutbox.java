package com.novelforge.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ProjectionOutbox {
    public static final String TARGET_NEO4J="NEO4J";
    public static final String TARGET_ELASTICSEARCH="ELASTICSEARCH";
    public static final String EVENT_MEMORY_SNAPSHOT="FORMAL_MEMORY_SNAPSHOT";
    public static final String EVENT_CONFIRMED_TEXT_SNAPSHOT="CONFIRMED_TEXT_SNAPSHOT";
    public static final int NEO4J_PROJECTION_SCHEMA_VERSION=Neo4jProjectionClient.PROJECTION_SCHEMA_VERSION;
    public static final int ELASTICSEARCH_PROJECTION_SCHEMA_VERSION=1;
    public record Event(String id,String novelId,String target,String eventType,String memoryHash,int attempts) {}
    public record Status(boolean enabled,int pending,int processing,int failed,int succeeded,int checkpoints) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final boolean neo4jEnabled;
    private final boolean elasticsearchEnabled;

    public ProjectionOutbox(JdbcTemplate jdbc,ObjectMapper mapper,TransactionTemplate transactions,
                            @Value("${novelforge.projections.neo4j.enabled:false}") boolean neo4jEnabled,
                            @Value("${novelforge.projections.elasticsearch.enabled:false}") boolean elasticsearchEnabled) {
        this.jdbc=jdbc; this.mapper=mapper; this.transactions=transactions;
        this.neo4jEnabled=neo4jEnabled; this.elasticsearchEnabled=elasticsearchEnabled;
    }

    public boolean neo4jEnabled() { return neo4jEnabled; }
    public boolean elasticsearchEnabled() { return elasticsearchEnabled; }

    public Status status() { return status(TARGET_NEO4J,neo4jEnabled); }
    public Status elasticsearchStatus() { return status(TARGET_ELASTICSEARCH,elasticsearchEnabled); }

    private Status status(String target,boolean enabled) {
        if(!enabled) return new Status(false,0,0,0,0,0);
        Map<String,Integer> counts=new LinkedHashMap<>();
        jdbc.query("SELECT status,COUNT(*) AS total FROM outbox_event WHERE target=? GROUP BY status",
                rs->{counts.put(rs.getString("status"),rs.getInt("total"));},target);
        Integer checkpoints=jdbc.queryForObject("SELECT COUNT(*) FROM projection_checkpoint WHERE target LIKE ?",Integer.class,target+":%");
        return new Status(true,counts.getOrDefault("PENDING",0)+counts.getOrDefault("RETRY",0),counts.getOrDefault("PROCESSING",0),
                counts.getOrDefault("FAILED",0),counts.getOrDefault("SUCCEEDED",0),checkpoints==null?0:checkpoints);
    }

    public void enqueueNeo4jSnapshot(String novelId,String memoryHash) {
        enqueue(novelId,TARGET_NEO4J,EVENT_MEMORY_SNAPSHOT,memoryHash,neo4jEnabled);
    }

    public void enqueueElasticsearchSnapshot(String novelId,String contentHash) {
        enqueue(novelId,TARGET_ELASTICSEARCH,EVENT_CONFIRMED_TEXT_SNAPSHOT,contentHash,elasticsearchEnabled);
    }

    private void enqueue(String novelId,String target,String eventType,String snapshotHash,boolean enabled) {
        if(!enabled) return;
        String projected=jdbc.query("SELECT metadata_json FROM projection_checkpoint WHERE target=?",
                (rs,index)->rs.getString(1),checkpoint(target,novelId)).stream().findFirst().orElse("");
        if(checkpointMatches(projected,target,snapshotHash)) return;
        Integer duplicate=jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE novel_id=? AND target=? AND event_type=? AND status IN ('PENDING','RETRY','PROCESSING') AND payload_hash=?",
                Integer.class,novelId,target,eventType,snapshotHash);
        if(duplicate!=null && duplicate>0) return;
        jdbc.update("UPDATE outbox_event SET status='SUPERSEDED',processed_at=? WHERE novel_id=? AND target=? AND event_type=? AND status IN ('PENDING','RETRY')",
                Instant.now().toString(),novelId,target,eventType);
        Map<String,Object> payload=new LinkedHashMap<>(); payload.put("schemaVersion",schemaVersion(target)); payload.put("memoryHash",snapshotHash);
        String payloadJson=json(payload);
        jdbc.update("INSERT INTO outbox_event(id,novel_id,target,event_type,aggregate_id,source_version_id,payload_json,payload_hash,status,attempts,created_at,next_attempt_at,processed_at,error) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),novelId,target,eventType,novelId,null,payloadJson,snapshotHash,
                "PENDING",0,Instant.now().toString(),null,null,null);
    }

    public void enqueueMissingNeo4jSnapshots() {
        enqueueMissing(TARGET_NEO4J,"NEO4J_SOURCE:",EVENT_MEMORY_SNAPSHOT,neo4jEnabled);
    }

    public void enqueueMissingElasticsearchSnapshots() {
        enqueueMissing(TARGET_ELASTICSEARCH,"CONFIRMED_TEXT:",EVENT_CONFIRMED_TEXT_SNAPSHOT,elasticsearchEnabled);
    }

    private void enqueueMissing(String target,String sourcePrefix,String eventType,boolean enabled) {
        if(!enabled) return;
        jdbc.update("UPDATE outbox_event SET status='RETRY',next_attempt_at=NULL,error=? WHERE target=? AND status='PROCESSING'",
                "服务重启后恢复未完成投影",target);
        List<Map.Entry<String,String>> formal=jdbc.query("SELECT target,metadata_json FROM projection_checkpoint WHERE target LIKE ?",
                (rs,index)->Map.entry(rs.getString(1).substring(sourcePrefix.length()),memoryHash(rs.getString(2))),sourcePrefix+"%");
        for(var item:formal) {
            String projected=jdbc.query("SELECT metadata_json FROM projection_checkpoint WHERE target=?",
                    (rs,index)->rs.getString(1),checkpoint(target,item.getKey())).stream().findFirst().orElse("");
            if(checkpointMatches(projected,target,item.getValue())) continue;
            if(TARGET_NEO4J.equals(target)) enqueueNeo4jSnapshot(item.getKey(),item.getValue());
            else if(TARGET_ELASTICSEARCH.equals(target)) enqueueElasticsearchSnapshot(item.getKey(),item.getValue());
            else throw new IllegalArgumentException("未知投影目标："+target+"/"+eventType);
        }
    }

    public Event claimNextNeo4j() { return claimNext(TARGET_NEO4J,neo4jEnabled); }
    public Event claimNextElasticsearch() { return claimNext(TARGET_ELASTICSEARCH,elasticsearchEnabled); }

    private Event claimNext(String target,boolean enabled) {
        if(!enabled) return null;
        return transactions.execute(status->{
            List<Event> events=jdbc.query("""
                    SELECT id,novel_id,target,event_type,payload_json,attempts FROM outbox_event
                    WHERE target=? AND status IN ('PENDING','RETRY') AND (next_attempt_at IS NULL OR next_attempt_at<=?)
                    ORDER BY created_at LIMIT 1
                    """,(rs,index)->new Event(rs.getString("id"),rs.getString("novel_id"),rs.getString("target"),rs.getString("event_type"),
                    memoryHash(rs.getString("payload_json")),rs.getInt("attempts")),target,Instant.now().toString());
            if(events.isEmpty()) return null;
            Event event=events.getFirst();
            int claimed=jdbc.update("UPDATE outbox_event SET status='PROCESSING',attempts=attempts+1,error=NULL WHERE id=? AND status IN ('PENDING','RETRY')",event.id);
            return claimed==1?new Event(event.id,event.novelId,event.target,event.eventType,event.memoryHash,event.attempts+1):null;
        });
    }

    public void succeeded(Event event) {
        transactions.executeWithoutResult(status->{
            String now=Instant.now().toString();
            jdbc.update("UPDATE outbox_event SET status='SUCCEEDED',processed_at=?,next_attempt_at=NULL,error=NULL WHERE id=?",now,event.id);
            Map<String,Object> metadata=new LinkedHashMap<>(); metadata.put("memoryHash",event.memoryHash); metadata.put("schemaVersion",schemaVersion(event.target));
            String metadataJson=json(metadata); String target=checkpoint(event.target,event.novelId);
            int updated=jdbc.update("UPDATE projection_checkpoint SET last_event_id=?,updated_at=?,metadata_json=? WHERE target=?",
                    event.id,now,metadataJson,target);
            if(updated==0) jdbc.update("INSERT INTO projection_checkpoint(target,last_event_id,updated_at,metadata_json) VALUES(?,?,?,?)",
                    target,event.id,now,metadataJson);
        });
    }

    public void failed(Event event,Throwable failure) {
        String message=failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage());
        if(message.length()>2000) message=message.substring(0,2000);
        boolean exhausted=event.attempts>=5;
        String next=exhausted?null:Instant.now().plus(Math.min(60,event.attempts*5L), ChronoUnit.SECONDS).toString();
        jdbc.update("UPDATE outbox_event SET status=?,next_attempt_at=?,processed_at=?,error=? WHERE id=?",
                exhausted?"FAILED":"RETRY",next,exhausted?Instant.now().toString():null,message,event.id);
    }

    private String checkpoint(String target,String novelId) { return target+":"+novelId; }
    private int schemaVersion(String target) {
        return TARGET_NEO4J.equals(target)?NEO4J_PROJECTION_SCHEMA_VERSION:ELASTICSEARCH_PROJECTION_SCHEMA_VERSION;
    }
    private boolean checkpointMatches(String json,String target,String snapshotHash) {
        if(json==null||json.isBlank()) return false;
        try {
            var metadata=mapper.readTree(json);
            return snapshotHash.equals(metadata.path("memoryHash").asText())
                    && metadata.path("schemaVersion").asInt(0)==schemaVersion(target);
        } catch(JsonProcessingException e) { return false; }
    }
    private String memoryHash(String json) {
        try { return mapper.readTree(json).path("memoryHash").asText(); }
        catch(JsonProcessingException e) { return ""; }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(JsonProcessingException e) { throw new IllegalStateException("无法序列化投影事件",e); }
    }
}
