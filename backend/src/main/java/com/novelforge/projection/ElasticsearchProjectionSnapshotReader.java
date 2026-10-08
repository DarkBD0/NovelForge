package com.novelforge.projection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ElasticsearchProjectionSnapshotReader {
    private final JdbcTemplate jdbc;
    public ElasticsearchProjectionSnapshotReader(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public List<Map<String,Object>> read(String novelId) {
        return jdbc.query("""
                SELECT a.id AS artifact_id,a.kind,a.chapter_number,av.id AS version_id,av.title,av.content,av.summary,
                       av.content_hash,av.created_at
                FROM artifact a JOIN artifact_version av ON av.id=a.approved_version_id AND av.novel_id=a.novel_id
                WHERE a.novel_id=? AND a.kind IN ('CHARACTERS','CHAPTER') AND a.needs_revision=FALSE AND av.dismissed=FALSE
                ORDER BY a.chapter_number,a.ordinal_no
                """,(rs,index)->{
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("id",novelId+":"+rs.getString("version_id")); item.put("novelId",novelId);
            item.put("artifactId",rs.getString("artifact_id")); item.put("sourceVersionId",rs.getString("version_id"));
            item.put("chapterNumber",rs.getInt("chapter_number")); item.put("chunkType",rs.getString("kind"));
            item.put("authorityState","CONFIRMED"); item.put("validFromChapter",rs.getInt("chapter_number"));
            item.put("confirmed",true); item.put("title",value(rs.getString("title")));
            item.put("text",value(rs.getString("content"))); item.put("summary",value(rs.getString("summary")));
            item.put("entityIds",List.of()); item.put("eventIds",List.of());
            item.put("contentHash",rs.getString("content_hash")); item.put("createdAt",rs.getString("created_at"));
            return item;
        },novelId);
    }

    private String value(String value) { return value==null?"":value; }
}
