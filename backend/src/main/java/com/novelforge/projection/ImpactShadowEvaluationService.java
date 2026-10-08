package com.novelforge.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.revision.ImpactAnalyzer;
import org.springframework.beans.factory.annotation.Value;
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

/** Read-only A/B evaluation of ImpactAnalyzer against the Neo4j version-dependency projection. */
@Service
public class ImpactShadowEvaluationService {
    public enum Status { DISABLED,NO_SAMPLES,MATCHED,MISMATCH,UNAVAILABLE }
    public record Comparison(String sourceArtifactId,String kind,int chapterNumber,
                             List<String> mysqlAffected,List<String> neo4jAffected,boolean matched,
                             List<String> missingInNeo4j,List<String> unexpectedInNeo4j) {}
    public record Report(Status status,boolean shadowOnly,String scope,int explicitDependencies,long latencyMs,
                         List<Comparison> comparisons,String diagnostic) {}

    private static final String SCOPE="VERSION_DEPENDENCY_IMPACT";
    private final NovelRepository repository;
    private final ImpactAnalyzer impact;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final URI commitUri;
    private final String authorization;
    private final String projectKey;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public ImpactShadowEvaluationService(NovelRepository repository,ImpactAnalyzer impact,ObjectMapper mapper,
            @Value("${novelforge.projections.neo4j.enabled:false}") boolean enabled,
            @Value("${novelforge.projections.neo4j.http-url:http://127.0.0.1:7474}") String httpUrl,
            @Value("${novelforge.projections.neo4j.username:}") String username,
            @Value("${novelforge.projections.neo4j.password:}") String password,
            @Value("${novelforge.projections.neo4j.project-key:novelforge-local}") String projectKey) {
        this.repository=repository; this.impact=impact; this.mapper=mapper; this.enabled=enabled; this.projectKey=projectKey;
        this.commitUri=URI.create(httpUrl.replaceAll("/+$","")+"/db/neo4j/tx/commit");
        this.authorization=username==null||username.isBlank()||password==null||password.isBlank()?"":
                "Basic "+Base64.getEncoder().encodeToString((username+":"+password).getBytes(StandardCharsets.UTF_8));
    }

    public boolean enabled() { return enabled; }

    public Report evaluate(String novelId) {
        Novel mysql=repository.get(novelId);
        if(!enabled) return new Report(Status.DISABLED,true,SCOPE,explicitDependencies(mysql),0,List.of(),"Neo4j 影子投影未启用");
        long started=System.nanoTime();
        try {
            Novel graph=readGraph(novelId);
            long latency=(System.nanoTime()-started)/1_000_000;
            List<Comparison> comparisons=compare(mysql,graph);
            boolean matched=comparisons.stream().allMatch(Comparison::matched);
            int samples=explicitDependencies(mysql);
            Status status=!matched?Status.MISMATCH:samples==0?Status.NO_SAMPLES:Status.MATCHED;
            String diagnostic=status==Status.NO_SAMPLES?"没有显式版本来源依赖；仅验证了兼容旧数据的顺序回退规则":"";
            return new Report(status,true,SCOPE,samples,latency,comparisons,diagnostic);
        } catch(Exception failure) {
            long latency=(System.nanoTime()-started)/1_000_000;
            String message=failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage());
            if(message.length()>1000) message=message.substring(0,1000);
            return new Report(Status.UNAVAILABLE,true,SCOPE,explicitDependencies(mysql),latency,List.of(),"Neo4j 查询失败："+message);
        }
    }

    private Novel readGraph(String novelId) throws Exception {
        List<Map<String,Object>> statements=List.of(
                statement("MATCH (m:NF_ProjectionMeta {projectKey:$projectKey}) RETURN m.schemaVersion AS schemaVersion",
                        Map.of("projectKey",projectKey)),
                statement("""
                        MATCH (a:NF_Artifact {novelId:$novelId})
                        OPTIONAL MATCH (v:NF_Version {novelId:$novelId})-[:NF_VERSION_OF_ARTIFACT]->(a)
                        RETURN a.artifactId,a.ordinal,a.kind,a.chapterNumber,a.batchNumber,a.approvedVersionId,
                               a.needsRevision,v.versionId,v.ordinal,v.dismissed,v.sourceVersionIds,v.planStartChapter
                        ORDER BY a.ordinal,v.ordinal
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
        if(results.size()!=2) throw new IllegalStateException("Neo4j 版本依赖查询返回数量错误");
        int schemaVersion=results.path(0).path("data").path(0).path("row").path(0).asInt(0);
        if(schemaVersion<Neo4jProjectionClient.PROJECTION_SCHEMA_VERSION)
            throw new IllegalStateException("Neo4j 投影模式版本过旧："+schemaVersion);
        return graphNovel(results.path(1));
    }

    private Novel graphNovel(JsonNode result) {
        Map<String,Novel.Artifact> artifacts=new LinkedHashMap<>();
        Map<String,Integer> ordinals=new LinkedHashMap<>();
        for(JsonNode data:result.path("data")) {
            JsonNode row=data.path("row"); String artifactId=text(row.path(0));
            if(artifactId==null) continue;
            Novel.Artifact artifact=artifacts.computeIfAbsent(artifactId,key->{
                Novel.Artifact value=new Novel.Artifact(); value.id=key;
                value.kind=Novel.Kind.valueOf(row.path(2).asText()); value.chapterNumber=row.path(3).asInt();
                value.batchNumber=row.path(4).asInt(); value.approvedVersionId=text(row.path(5));
                value.needsRevision=row.path(6).asBoolean(); ordinals.put(key,row.path(1).asInt()); return value;
            });
            String versionId=text(row.path(7));
            if(versionId==null) continue;
            Novel.Version version=new Novel.Version(); version.id=versionId; version.dismissed=row.path(9).asBoolean();
            version.sourceVersionIds=strings(row.path(10));
            if(!row.path(11).isNull()&&!row.path(11).isMissingNode()) {
                version.plan=new Novel.Plan(); version.plan.startChapter=row.path(11).asInt();
            }
            artifact.versions.add(version);
        }
        Novel novel=new Novel();
        novel.artifacts.addAll(artifacts.values().stream().sorted(Comparator.comparingInt(item->ordinals.get(item.id))).toList());
        return novel;
    }

    private List<Comparison> compare(Novel mysql,Novel graph) {
        Map<String,Novel.Artifact> left=index(mysql),right=index(graph);
        LinkedHashSet<String> ids=new LinkedHashSet<>();
        mysql.artifacts.forEach(item->ids.add(item.id)); graph.artifacts.forEach(item->ids.add(item.id));
        List<Comparison> result=new ArrayList<>();
        for(String id:ids) {
            Novel.Artifact source=left.get(id),graphSource=right.get(id);
            List<String> mysqlAffected=source==null?List.of():impact.previewFollowing(mysql,source);
            List<String> neoAffected=graphSource==null?List.of():impact.previewFollowing(graph,graphSource);
            Set<String> missing=new LinkedHashSet<>(mysqlAffected); missing.removeAll(neoAffected);
            Set<String> unexpected=new LinkedHashSet<>(neoAffected); unexpected.removeAll(mysqlAffected);
            Novel.Artifact display=source==null?graphSource:source;
            result.add(new Comparison(id,display.kind.name(),display.chapterNumber,List.copyOf(mysqlAffected),List.copyOf(neoAffected),
                    missing.isEmpty()&&unexpected.isEmpty(),List.copyOf(missing),List.copyOf(unexpected)));
        }
        return List.copyOf(result);
    }

    private Map<String,Novel.Artifact> index(Novel novel) {
        Map<String,Novel.Artifact> result=new LinkedHashMap<>();
        for(var artifact:novel.artifacts) result.put(artifact.id,artifact);
        return result;
    }
    private int explicitDependencies(Novel novel) {
        int count=0;
        for(var artifact:novel.artifacts) {
            Novel.Version basis=artifact.approved()!=null?artifact.approved():artifact.latest();
            if(basis!=null&&basis.sourceVersionIds!=null) count+=basis.sourceVersionIds.size();
        }
        return count;
    }
    private List<String> strings(JsonNode node) {
        if(!node.isArray()) return List.of();
        List<String> values=new ArrayList<>(); for(JsonNode item:node) if(item.isTextual()) values.add(item.asText());
        return List.copyOf(values);
    }
    private String text(JsonNode node) { return node==null||node.isNull()||node.isMissingNode()?null:node.asText(); }
    private Map<String,Object> statement(String cypher,Map<String,Object> parameters) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("statement",cypher); result.put("parameters",parameters); return result;
    }
}
