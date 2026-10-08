package com.novelforge.projection;

import com.novelforge.shared.Problem;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ElasticsearchShadowSearchService {
    public record Run(String id,String novelId,String query,int beforeChapter,int limit,String status,int hitCount,
                      long latencyMs,String createdAt,String diagnostic,List<ElasticsearchProjectionClient.Hit> hits) {}

    private final JdbcTemplate jdbc;
    private final ProjectionOutbox outbox;
    private final ObjectProvider<ElasticsearchProjectionClient> clients;

    public ElasticsearchShadowSearchService(JdbcTemplate jdbc,ProjectionOutbox outbox,
                                            ObjectProvider<ElasticsearchProjectionClient> clients) {
        this.jdbc=jdbc; this.outbox=outbox; this.clients=clients;
    }

    public Run search(String novelId,String query,int beforeChapter,int limit) {
        if(!outbox.elasticsearchEnabled()) throw new Problem(409,"Elasticsearch 影子召回尚未启用");
        Integer novels=jdbc.queryForObject("SELECT COUNT(*) FROM novel_project WHERE id=?",Integer.class,novelId);
        if(novels==null||novels==0) throw new Problem(404,"小说不存在");
        ElasticsearchProjectionClient client=clients.getIfAvailable();
        if(client==null) throw new Problem(503,"Elasticsearch 影子召回不可用");
        String id=UUID.randomUUID().toString(); String createdAt=Instant.now().toString(); long started=System.nanoTime();
        try {
            List<ElasticsearchProjectionClient.Hit> hits=client.search(novelId,query,beforeChapter,limit);
            long latency=(System.nanoTime()-started)/1_000_000L;
            jdbc.update("INSERT INTO shadow_retrieval_run(id,novel_id,query_text,before_chapter,result_limit,status,hit_count,latency_ms,created_at,diagnostic) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id,novelId,query,beforeChapter,limit,"SUCCEEDED",hits.size(),latency,createdAt,null);
            for(int i=0;i<hits.size();i++) {
                var hit=hits.get(i);
                jdbc.update("INSERT INTO shadow_retrieval_hit(id,run_id,novel_id,rank_no,source_version_id,artifact_id,chapter_number,score,title,summary) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID().toString(),id,novelId,i+1,hit.sourceVersionId(),hit.artifactId(),hit.chapterNumber(),
                        hit.score(),hit.title(),hit.summary());
            }
            return new Run(id,novelId,query,beforeChapter,limit,"SUCCEEDED",hits.size(),latency,createdAt,null,hits);
        } catch(RuntimeException failure) {
            long latency=(System.nanoTime()-started)/1_000_000L;
            String diagnostic=failure.getClass().getSimpleName();
            jdbc.update("INSERT INTO shadow_retrieval_run(id,novel_id,query_text,before_chapter,result_limit,status,hit_count,latency_ms,created_at,diagnostic) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id,novelId,query,beforeChapter,limit,"FAILED",0,latency,createdAt,diagnostic);
            throw new Problem(503,"Elasticsearch 影子召回失败，未影响正式写作流程");
        }
    }

    public List<Run> list(String novelId) {
        return jdbc.query("SELECT * FROM shadow_retrieval_run WHERE novel_id=? ORDER BY created_at DESC",
                (rs,index)->new Run(rs.getString("id"),novelId,rs.getString("query_text"),rs.getInt("before_chapter"),
                        rs.getInt("result_limit"),rs.getString("status"),rs.getInt("hit_count"),rs.getLong("latency_ms"),
                        rs.getString("created_at"),rs.getString("diagnostic"),hits(rs.getString("id"))),novelId);
    }

    private List<ElasticsearchProjectionClient.Hit> hits(String runId) {
        return jdbc.query("SELECT * FROM shadow_retrieval_hit WHERE run_id=? ORDER BY rank_no",
                (rs,index)->new ElasticsearchProjectionClient.Hit(rs.getString("source_version_id"),rs.getString("artifact_id"),
                        rs.getInt("chapter_number"),rs.getDouble("score"),rs.getString("title"),rs.getString("summary"),""),runId);
    }
}
