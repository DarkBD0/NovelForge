package com.novelforge.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class Neo4jProjectionSnapshotReader {
    public record Snapshot(Map<String,Object> novel,List<Map<String,Object>> chapters,List<Map<String,Object>> versions,
                           List<Map<String,Object>> facts,List<Map<String,Object>> events,List<Map<String,Object>> foreshadows,
                           List<Map<String,Object>> entities,List<Map<String,Object>> relations,
                           List<Map<String,Object>> artifacts,List<Map<String,Object>> dependencies) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public Neo4jProjectionSnapshotReader(JdbcTemplate jdbc,ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }

    public Snapshot read(String novelId) {
        Map<String,Object> novel=jdbc.queryForMap("SELECT id,title,revision,status FROM novel_project WHERE id=?",novelId);
        List<Map<String,Object>> chapters=jdbc.query("""
                SELECT a.chapter_number,av.id AS version_id,av.title,av.summary
                FROM artifact a JOIN artifact_version av ON av.id=a.approved_version_id AND av.novel_id=a.novel_id
                WHERE a.novel_id=? AND a.kind='CHAPTER' AND a.needs_revision=FALSE AND av.dismissed=FALSE
                ORDER BY a.chapter_number
                """,(rs,index)->map("chapterNumber",rs.getInt("chapter_number"),"versionId",rs.getString("version_id"),
                "title",rs.getString("title"),"summary",rs.getString("summary")),novelId);
        List<Map<String,Object>> versions=jdbc.query("""
                SELECT av.id,av.artifact_id,av.ordinal_no,a.kind,a.chapter_number,av.title,av.summary,av.metadata_json,av.dismissed
                FROM artifact_version av JOIN artifact a ON a.id=av.artifact_id AND a.novel_id=av.novel_id
                WHERE av.novel_id=? ORDER BY a.ordinal_no,av.ordinal_no
                """,(rs,index)->{
            JsonNode metadata=json(rs.getString("metadata_json"));
            List<String> sourceVersionIds=strings(metadata.path("sourceVersionIds"));
            Integer planStart=metadata.path("plan").path("startChapter").isInt()?metadata.path("plan").path("startChapter").asInt():null;
            return map("versionId",rs.getString("id"),"artifactId",rs.getString("artifact_id"),
                    "ordinal",rs.getInt("ordinal_no"),"kind",rs.getString("kind"),"chapterNumber",rs.getInt("chapter_number"),
                    "title",rs.getString("title"),"summary",rs.getString("summary"),"dismissed",rs.getBoolean("dismissed"),
                    "sourceVersionIds",sourceVersionIds,"planStartChapter",planStart);
        },novelId);
        Map<String,List<Map<String,Object>>> versionsByArtifact=new HashMap<>();
        for(var version:versions) versionsByArtifact.computeIfAbsent((String)version.get("artifactId"),key->new ArrayList<>()).add(version);
        List<Map<String,Object>> artifacts=jdbc.query("""
                SELECT id,ordinal_no,kind,chapter_number,batch_number,approved_version_id,needs_revision
                FROM artifact WHERE novel_id=? ORDER BY ordinal_no
                """,(rs,index)->{
            String artifactId=rs.getString("id");
            List<Map<String,Object>> artifactVersions=versionsByArtifact.getOrDefault(artifactId,List.of());
            Map<String,Object> latest=null;
            for(var version:artifactVersions) if(!Boolean.TRUE.equals(version.get("dismissed"))) latest=version;
            String approved=rs.getString("approved_version_id");
            String basis=approved!=null?approved:latest==null?null:(String)latest.get("versionId");
            Integer latestPlanStart=latest==null?null:(Integer)latest.get("planStartChapter");
            return map("artifactId",artifactId,"ordinal",rs.getInt("ordinal_no"),"kind",rs.getString("kind"),
                    "chapterNumber",rs.getInt("chapter_number"),"batchNumber",rs.getInt("batch_number"),
                    "approvedVersionId",approved,"basisVersionId",basis,"latestPlanStartChapter",latestPlanStart,
                    "needsRevision",rs.getBoolean("needs_revision"));
        },novelId);
        List<Map<String,Object>> dependencies=new ArrayList<>();
        for(var version:versions) for(String source:stringValues(version.get("sourceVersionIds")))
            dependencies.add(map("versionId",version.get("versionId"),"sourceVersionId",source));
        List<Map<String,Object>> facts=jdbc.query("""
                SELECT id,fact_type,fact_key,detail,fact_state,source_version_id,valid_from_chapter
                FROM canon_fact WHERE novel_id=? AND status='CURRENT' ORDER BY valid_from_chapter,fact_type,fact_key
                """,(rs,index)->map("factId",rs.getString("id"),"type",rs.getString("fact_type"),"key",rs.getString("fact_key"),
                "detail",rs.getString("detail"),"state",rs.getString("fact_state"),"sourceVersionId",rs.getString("source_version_id"),
                "chapterNumber",rs.getInt("valid_from_chapter")),novelId);
        List<Map<String,Object>> events=jdbc.query("""
                SELECT id,event_type,title,summary,chapter_number,sequence_no,time_text,source_version_id,status
                FROM story_event WHERE novel_id=? ORDER BY chapter_number,sequence_no
                """,(rs,index)->map("eventId",rs.getString("id"),"type",rs.getString("event_type"),"title",rs.getString("title"),
                "summary",rs.getString("summary"),"chapterNumber",rs.getInt("chapter_number"),"sequence",rs.getInt("sequence_no"),
                "timeText",rs.getString("time_text"),"sourceVersionId",rs.getString("source_version_id"),"status",rs.getString("status")),novelId);
        List<Map<String,Object>> foreshadows=jdbc.query("""
                SELECT id,title,description,planted_chapter,developed_chapters_json,resolved_chapter,status,source_version_id
                FROM foreshadow WHERE novel_id=? ORDER BY planted_chapter,title
                """,(rs,index)->map("foreshadowId",rs.getString("id"),"title",rs.getString("title"),"description",rs.getString("description"),
                "plantedChapter",nullableInteger(rs,"planted_chapter"),"developedChaptersJson",rs.getString("developed_chapters_json"),
                "resolvedChapter",nullableInteger(rs,"resolved_chapter"),"status",rs.getString("status"),
                "sourceVersionId",rs.getString("source_version_id")),novelId);
        List<Map<String,Object>> entities=jdbc.query("""
                SELECT id,entity_key,entity_type,canonical_name,aliases_json,description,source_version_id,
                       valid_from_chapter,valid_to_chapter,status
                FROM canon_entity WHERE novel_id=? ORDER BY valid_from_chapter,entity_type,entity_key
                """,(rs,index)->map("entityId",rs.getString("id"),"key",rs.getString("entity_key"),
                "type",rs.getString("entity_type"),"name",rs.getString("canonical_name"),
                "aliasesJson",rs.getString("aliases_json"),"description",rs.getString("description"),
                "sourceVersionId",rs.getString("source_version_id"),"validFromChapter",rs.getInt("valid_from_chapter"),
                "validToChapter",nullableInteger(rs,"valid_to_chapter"),"status",rs.getString("status")),novelId);
        List<Map<String,Object>> relations=jdbc.query("""
                SELECT id,relation_key,from_entity_id,relation_type,to_entity_id,detail,source_version_id,
                       valid_from_chapter,valid_to_chapter,status
                FROM entity_relation WHERE novel_id=? ORDER BY valid_from_chapter,relation_type,relation_key
                """,(rs,index)->map("relationId",rs.getString("id"),"key",rs.getString("relation_key"),
                "fromEntityId",rs.getString("from_entity_id"),"type",rs.getString("relation_type"),
                "toEntityId",rs.getString("to_entity_id"),"detail",rs.getString("detail"),
                "sourceVersionId",rs.getString("source_version_id"),"validFromChapter",rs.getInt("valid_from_chapter"),
                "validToChapter",nullableInteger(rs,"valid_to_chapter"),"status",rs.getString("status")),novelId);
        return new Snapshot(Collections.unmodifiableMap(new LinkedHashMap<>(novel)),chapters,versions,facts,events,foreshadows,
                entities,relations,artifacts,List.copyOf(dependencies));
    }

    private Integer nullableInteger(java.sql.ResultSet rs,String name) throws java.sql.SQLException {
        int value=rs.getInt(name); return rs.wasNull()?null:value;
    }
    private Map<String,Object> map(Object... values) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2) result.put((String)values[i],values[i+1]);
        return result;
    }
    private JsonNode json(String value) {
        try { return mapper.readTree(value==null?"{}":value); }
        catch(JsonProcessingException e) { throw new IllegalStateException("无法读取版本依赖元数据",e); }
    }
    private List<String> strings(JsonNode node) {
        if(!node.isArray()) return List.of();
        List<String> values=new ArrayList<>();
        for(JsonNode item:node) if(item.isTextual()&&!item.asText().isBlank()) values.add(item.asText());
        return List.copyOf(values);
    }
    private List<String> stringValues(Object value) {
        if(!(value instanceof List<?> items)) return List.of();
        List<String> values=new ArrayList<>();
        for(Object item:items) if(item instanceof String text&&!text.isBlank()) values.add(text);
        return List.copyOf(values);
    }
}
