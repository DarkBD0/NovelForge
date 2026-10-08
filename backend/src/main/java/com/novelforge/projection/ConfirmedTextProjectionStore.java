package com.novelforge.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tracks the exact confirmed text set independently from derived formal facts. */
@Component
public class ConfirmedTextProjectionStore {
    private static final String CHECKPOINT_PREFIX="CONFIRMED_TEXT:";
    private static final int SCHEMA_VERSION=3;
    public record SyncResult(boolean changed,String contentHash,int documents) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ProjectionOutbox outbox;
    private final boolean enabled;

    public ConfirmedTextProjectionStore(JdbcTemplate jdbc,ObjectMapper mapper,ProjectionOutbox outbox,
                                        @Value("${novelforge.storage.mode:legacy}") String storageMode) {
        this.jdbc=jdbc; this.mapper=mapper; this.outbox=outbox;
        this.enabled="normalized".equalsIgnoreCase(storageMode);
    }

    public SyncResult synchronize(Novel novel) {
        if(!enabled) return new SyncResult(false,"",0);
        List<Map<String,Object>> documents=new ArrayList<>();
        for(Novel.Artifact artifact:novel.artifacts) {
            if(artifact.approvedVersionId==null || artifact.approved()==null || artifact.needsRevision
                    || (artifact.kind!=Novel.Kind.CHARACTERS && artifact.kind!=Novel.Kind.CHAPTER)) continue;
            Novel.Version version=artifact.approved();
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("artifactId",artifact.id); item.put("sourceVersionId",version.id); item.put("kind",artifact.kind.name());
            item.put("chapterNumber",artifact.chapterNumber); item.put("title",value(version.title));
            item.put("text",value(version.content)); item.put("summary",value(version.summary));
            item.put("createdAt",value(version.createdAt));
            documents.add(item);
        }
        String contentHash=hash(SCHEMA_VERSION+"|"+json(documents));
        String target=CHECKPOINT_PREFIX+novel.id;
        String previous=jdbc.query("SELECT metadata_json FROM projection_checkpoint WHERE target=?",
                (rs,index)->metadataHash(rs.getString(1)),target).stream().findFirst().orElse("");
        if(contentHash.equals(previous)) return new SyncResult(false,contentHash,documents.size());

        Map<String,Object> metadata=new LinkedHashMap<>();
        metadata.put("memoryHash",contentHash); metadata.put("documents",documents.size()); metadata.put("schemaVersion",SCHEMA_VERSION);
        String now=Instant.now().toString(); String metadataJson=json(metadata);
        int updated=jdbc.update("UPDATE projection_checkpoint SET last_event_id=?,updated_at=?,metadata_json=? WHERE target=?",
                null,now,metadataJson,target);
        if(updated==0) jdbc.update("INSERT INTO projection_checkpoint(target,last_event_id,updated_at,metadata_json) VALUES(?,?,?,?)",
                target,null,now,metadataJson);
        outbox.enqueueElasticsearchSnapshot(novel.id,contentHash);
        return new SyncResult(true,contentHash,documents.size());
    }

    private String metadataHash(String metadata) {
        try { return mapper.readTree(metadata).path("memoryHash").asText(); }
        catch(JsonProcessingException e) { return ""; }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(JsonProcessingException e) { throw new IllegalStateException("无法序列化已确认文本投影",e); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String value(String value) { return value==null?"":value; }
}
