package com.novelforge.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compares rebuildable Neo4j relation queries with authoritative MySQL rows. */
@Service
public class RelationShadowEvaluationService {
    public enum Status { DISABLED,NO_SAMPLES,MATCHED,MISMATCH,UNAVAILABLE }
    public record Comparison(String query,int mysqlCount,int neo4jCount,boolean matched,
                             List<String> missingInNeo4j,List<String> unexpectedInNeo4j) {}
    public record Report(Status status,boolean shadowOnly,String scope,long latencyMs,
                         List<Comparison> comparisons,String diagnostic) {}

    private static final int DIFFERENCE_LIMIT=50;
    private static final String SCOPE="ENTITY_RELATIONS_ONLY";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final URI commitUri;
    private final String authorization;
    private final String projectKey;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public RelationShadowEvaluationService(JdbcTemplate jdbc,ObjectMapper mapper,
            @Value("${novelforge.projections.neo4j.enabled:false}") boolean enabled,
            @Value("${novelforge.projections.neo4j.http-url:http://127.0.0.1:7474}") String httpUrl,
            @Value("${novelforge.projections.neo4j.username:}") String username,
            @Value("${novelforge.projections.neo4j.password:}") String password,
            @Value("${novelforge.projections.neo4j.project-key:novelforge-local}") String projectKey) {
        this.jdbc=jdbc; this.mapper=mapper; this.enabled=enabled; this.projectKey=projectKey;
        this.commitUri=URI.create(httpUrl.replaceAll("/+$","")+"/db/neo4j/tx/commit");
        this.authorization=username==null||username.isBlank()||password==null||password.isBlank()?"":
                "Basic "+Base64.getEncoder().encodeToString((username+":"+password).getBytes(StandardCharsets.UTF_8));
    }

    public boolean enabled() { return enabled; }

    public Report evaluate(String novelId) {
        Map<String,List<String>> expected=mysqlQuerySet(novelId);
        if(!enabled) return report(Status.DISABLED,0,expected,Map.of(),"Neo4j 影子投影未启用");
        long started=System.nanoTime();
        try {
            Map<String,List<String>> actual=neo4jQuerySet(novelId);
            long latency=(System.nanoTime()-started)/1_000_000;
            List<Comparison> comparisons=compare(expected,actual);
            boolean matched=comparisons.stream().allMatch(Comparison::matched);
            boolean sampled=!expected.get("ENTITY_KEYS").isEmpty() || !expected.get("ALL_RELATIONS").isEmpty();
            Status status=!matched?Status.MISMATCH:sampled?Status.MATCHED:Status.NO_SAMPLES;
            String diagnostic=status==Status.NO_SAMPLES?"正式实体关系为空；零行一致不计为效果样本":"";
            return new Report(status,true,SCOPE,latency,comparisons,diagnostic);
        } catch(Exception failure) {
            long latency=(System.nanoTime()-started)/1_000_000;
            String message=failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage());
            if(message.length()>1000) message=message.substring(0,1000);
            return report(Status.UNAVAILABLE,latency,expected,Map.of(),"Neo4j 查询失败："+message);
        }
    }

    private Map<String,List<String>> mysqlQuerySet(String novelId) {
        Map<String,List<String>> result=new LinkedHashMap<>();
        result.put("ENTITY_KEYS",jdbc.query("""
                SELECT entity_key,entity_type,canonical_name,status,source_version_id,valid_from_chapter,valid_to_chapter
                FROM canon_entity WHERE novel_id=? ORDER BY entity_key
                """,(rs,index)->row(rs.getString("entity_key"),rs.getString("entity_type"),rs.getString("canonical_name"),
                rs.getString("status"),rs.getString("source_version_id"),rs.getObject("valid_from_chapter"),
                rs.getObject("valid_to_chapter")),novelId));
        String relationSql="""
                SELECT er.relation_key,source.entity_key AS source_key,er.relation_type,target.entity_key AS target_key,
                       er.status,er.source_version_id,er.valid_from_chapter,er.valid_to_chapter
                FROM entity_relation er
                JOIN canon_entity source ON source.id=er.from_entity_id AND source.novel_id=er.novel_id
                JOIN canon_entity target ON target.id=er.to_entity_id AND target.novel_id=er.novel_id
                WHERE er.novel_id=? %s ORDER BY er.relation_key
                """;
        result.put("ALL_RELATIONS",jdbc.query(relationSql.formatted(""),this::relationRow,novelId));
        result.put("ACTIVE_RELATIONS",jdbc.query(relationSql.formatted("AND er.status='ACTIVE'"),this::relationRow,novelId));
        result.put("ACTIVE_TWO_HOP_PATHS",jdbc.query("""
                SELECT source.entity_key AS source_key,r1.relation_key AS first_relation,via.entity_key AS via_key,
                       r2.relation_key AS second_relation,target.entity_key AS target_key
                FROM entity_relation r1
                JOIN entity_relation r2 ON r2.novel_id=r1.novel_id AND r2.from_entity_id=r1.to_entity_id
                JOIN canon_entity source ON source.id=r1.from_entity_id AND source.novel_id=r1.novel_id
                JOIN canon_entity via ON via.id=r1.to_entity_id AND via.novel_id=r1.novel_id
                JOIN canon_entity target ON target.id=r2.to_entity_id AND target.novel_id=r2.novel_id
                WHERE r1.novel_id=? AND r1.status='ACTIVE' AND r2.status='ACTIVE'
                ORDER BY source.entity_key,r1.relation_key,r2.relation_key,target.entity_key
                """,(rs,index)->row(rs.getString("source_key"),rs.getString("first_relation"),rs.getString("via_key"),
                rs.getString("second_relation"),rs.getString("target_key")),novelId));
        return result;
    }

    private String relationRow(java.sql.ResultSet rs,int index) throws java.sql.SQLException {
        return row(rs.getString("relation_key"),rs.getString("source_key"),rs.getString("relation_type"),
                rs.getString("target_key"),rs.getString("status"),rs.getString("source_version_id"),
                rs.getObject("valid_from_chapter"),rs.getObject("valid_to_chapter"));
    }

    private Map<String,List<String>> neo4jQuerySet(String novelId) throws Exception {
        List<Map<String,Object>> statements=List.of(
                statement("MATCH (m:NF_ProjectionMeta {projectKey:$projectKey}) RETURN m.schemaVersion AS schemaVersion",
                        Map.of("projectKey",projectKey)),
                statement("""
                        MATCH (e:NF_Entity {novelId:$novelId})
                        RETURN e.key,e.type,e.name,e.status,e.sourceVersionId,e.validFromChapter,e.validToChapter ORDER BY e.key
                        """,Map.of("novelId",novelId)),
                statement("""
                        MATCH (source:NF_Entity {novelId:$novelId})-[r:NF_ENTITY_RELATION]->(target:NF_Entity {novelId:$novelId})
                        RETURN r.key,source.key,r.type,target.key,r.status,r.sourceVersionId,r.validFromChapter,r.validToChapter ORDER BY r.key
                        """,Map.of("novelId",novelId)),
                statement("""
                        MATCH (source:NF_Entity {novelId:$novelId})-[r:NF_ENTITY_RELATION {status:'ACTIVE'}]->(target:NF_Entity {novelId:$novelId})
                        RETURN r.key,source.key,r.type,target.key,r.status,r.sourceVersionId,r.validFromChapter,r.validToChapter ORDER BY r.key
                        """,Map.of("novelId",novelId)),
                statement("""
                        MATCH (source:NF_Entity {novelId:$novelId})-[r1:NF_ENTITY_RELATION {status:'ACTIVE'}]->
                              (via:NF_Entity {novelId:$novelId})-[r2:NF_ENTITY_RELATION {status:'ACTIVE'}]->
                              (target:NF_Entity {novelId:$novelId})
                        RETURN source.key,r1.key,via.key,r2.key,target.key
                        ORDER BY source.key,r1.key,r2.key,target.key
                        """,Map.of("novelId",novelId)));
        String body=mapper.writeValueAsString(Map.of("statements",statements));
        HttpRequest.Builder request=HttpRequest.newBuilder(commitUri).timeout(Duration.ofSeconds(20))
                .header("Content-Type","application/json");
        if(!authorization.isBlank()) request.header("Authorization",authorization);
        HttpResponse<String> response=client.send(request.POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()<200||response.statusCode()>=300) throw new IllegalStateException("Neo4j HTTP "+response.statusCode());
        JsonNode json=mapper.readTree(response.body());
        if(json.path("errors").isArray()&&!json.path("errors").isEmpty())
            throw new IllegalStateException(json.path("errors").path(0).path("code").asText("Neo4jError")+": "+
                    json.path("errors").path(0).path("message").asText("查询失败"));
        JsonNode results=json.path("results");
        if(results.size()!=5) throw new IllegalStateException("Neo4j 固定查询集返回数量错误");
        int schemaVersion=results.path(0).path("data").path(0).path("row").path(0).asInt(0);
        if(schemaVersion<Neo4jProjectionClient.PROJECTION_SCHEMA_VERSION)
            throw new IllegalStateException("Neo4j 投影模式版本过旧："+schemaVersion);
        Map<String,List<String>> result=new LinkedHashMap<>();
        result.put("ENTITY_KEYS",rows(results.path(1)));
        result.put("ALL_RELATIONS",rows(results.path(2)));
        result.put("ACTIVE_RELATIONS",rows(results.path(3)));
        result.put("ACTIVE_TWO_HOP_PATHS",rows(results.path(4)));
        return result;
    }

    private List<String> rows(JsonNode result) {
        List<String> rows=new ArrayList<>();
        for(JsonNode data:result.path("data")) {
            List<Object> values=new ArrayList<>();
            for(JsonNode value:data.path("row")) values.add(value.isNull()?null:value.isNumber()?value.numberValue():value.asText());
            rows.add(row(values.toArray()));
        }
        rows.sort(Comparator.naturalOrder());
        return List.copyOf(rows);
    }

    private List<Comparison> compare(Map<String,List<String>> expected,Map<String,List<String>> actual) {
        List<Comparison> comparisons=new ArrayList<>();
        for(String query:expected.keySet()) {
            List<String> left=expected.getOrDefault(query,List.of());
            List<String> right=actual.getOrDefault(query,List.of());
            Set<String> missing=new LinkedHashSet<>(left); missing.removeAll(right);
            Set<String> unexpected=new LinkedHashSet<>(right); unexpected.removeAll(left);
            comparisons.add(new Comparison(query,left.size(),right.size(),missing.isEmpty()&&unexpected.isEmpty(),
                    missing.stream().limit(DIFFERENCE_LIMIT).toList(),unexpected.stream().limit(DIFFERENCE_LIMIT).toList()));
        }
        return List.copyOf(comparisons);
    }

    private Report report(Status status,long latency,Map<String,List<String>> expected,Map<String,List<String>> actual,String diagnostic) {
        return new Report(status,true,SCOPE,latency,compare(expected,actual),diagnostic);
    }
    private Map<String,Object> statement(String cypher,Map<String,Object> parameters) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("statement",cypher); result.put("parameters",parameters); return result;
    }
    private String row(Object... values) {
        StringBuilder result=new StringBuilder();
        for(int i=0;i<values.length;i++) {
            if(i>0) result.append('\u001f');
            Object value=values[i]; result.append(value==null?"<null>":String.valueOf(value).replace("\u001f"," "));
        }
        return result.toString();
    }
}
