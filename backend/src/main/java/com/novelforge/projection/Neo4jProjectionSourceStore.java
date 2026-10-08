package com.novelforge.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Tracks every MySQL field that participates in the rebuildable Neo4j graph. */
@Component
public class Neo4jProjectionSourceStore {
    private static final String CHECKPOINT_PREFIX="NEO4J_SOURCE:";
    public record SyncResult(boolean changed,String snapshotHash,int artifacts,int versions,int dependencies) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Neo4jProjectionSnapshotReader snapshots;
    private final ProjectionOutbox outbox;
    private final boolean enabled;

    public Neo4jProjectionSourceStore(JdbcTemplate jdbc,ObjectMapper mapper,Neo4jProjectionSnapshotReader snapshots,
                                      ProjectionOutbox outbox,
                                      @Value("${novelforge.storage.mode:legacy}") String storageMode,
                                      @Value("${novelforge.projections.neo4j.enabled:false}") boolean neo4jEnabled) {
        this.jdbc=jdbc; this.mapper=mapper; this.snapshots=snapshots; this.outbox=outbox;
        this.enabled="normalized".equalsIgnoreCase(storageMode)&&neo4jEnabled;
    }

    public SyncResult synchronize(String novelId) {
        if(!enabled) return new SyncResult(false,"",0,0,0);
        var snapshot=snapshots.read(novelId);
        String snapshotHash=hash(Neo4jProjectionClient.PROJECTION_SCHEMA_VERSION+"|"+json(snapshot));
        String target=CHECKPOINT_PREFIX+novelId;
        String previous=jdbc.query("SELECT metadata_json FROM projection_checkpoint WHERE target=?",
                (rs,index)->metadataHash(rs.getString(1)),target).stream().findFirst().orElse("");
        if(snapshotHash.equals(previous)) return result(false,snapshotHash,snapshot);

        Map<String,Object> metadata=new LinkedHashMap<>();
        metadata.put("memoryHash",snapshotHash); metadata.put("schemaVersion",Neo4jProjectionClient.PROJECTION_SCHEMA_VERSION);
        metadata.put("artifacts",snapshot.artifacts().size()); metadata.put("versions",snapshot.versions().size());
        metadata.put("dependencies",snapshot.dependencies().size()); metadata.put("entities",snapshot.entities().size());
        metadata.put("relations",snapshot.relations().size());
        String now=Instant.now().toString(); String metadataJson=json(metadata);
        int updated=jdbc.update("UPDATE projection_checkpoint SET last_event_id=?,updated_at=?,metadata_json=? WHERE target=?",
                null,now,metadataJson,target);
        if(updated==0) jdbc.update("INSERT INTO projection_checkpoint(target,last_event_id,updated_at,metadata_json) VALUES(?,?,?,?)",
                target,null,now,metadataJson);
        outbox.enqueueNeo4jSnapshot(novelId,snapshotHash);
        return result(true,snapshotHash,snapshot);
    }

    private SyncResult result(boolean changed,String hash,Neo4jProjectionSnapshotReader.Snapshot snapshot) {
        return new SyncResult(changed,hash,snapshot.artifacts().size(),snapshot.versions().size(),snapshot.dependencies().size());
    }
    private String metadataHash(String metadata) {
        try { return mapper.readTree(metadata).path("memoryHash").asText(); }
        catch(JsonProcessingException e) { return ""; }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(JsonProcessingException e) { throw new IllegalStateException("无法序列化 Neo4j 投影来源",e); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
