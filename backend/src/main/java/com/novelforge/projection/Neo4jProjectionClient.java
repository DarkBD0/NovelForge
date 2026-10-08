package com.novelforge.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name="novelforge.projections.neo4j.enabled",havingValue="true")
public class Neo4jProjectionClient {
    public static final int PROJECTION_SCHEMA_VERSION=3;
    private final ObjectMapper mapper;
    private final Neo4jProjectionSnapshotReader snapshots;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI commitUri;
    private final String authorization;
    private final String projectKey;

    public Neo4jProjectionClient(ObjectMapper mapper,Neo4jProjectionSnapshotReader snapshots,
                                 @Value("${novelforge.projections.neo4j.http-url}") String httpUrl,
                                 @Value("${novelforge.projections.neo4j.username}") String username,
                                 @Value("${novelforge.projections.neo4j.password}") String password,
                                 @Value("${novelforge.projections.neo4j.project-key:novelforge-local}") String projectKey) {
        if(username==null||username.isBlank()||password==null||password.isBlank()) throw new IllegalStateException("已启用 Neo4j 投影，但本机账号配置不完整");
        URI base=URI.create(httpUrl);
        if(!List.of("http","https").contains(base.getScheme())) throw new IllegalStateException("Neo4j HTTP 地址必须使用 http 或 https");
        this.mapper=mapper; this.snapshots=snapshots; this.projectKey=projectKey;
        this.commitUri=URI.create(httpUrl.replaceAll("/+$","")+"/db/neo4j/tx/commit");
        this.authorization="Basic "+Base64.getEncoder().encodeToString((username+":"+password).getBytes(StandardCharsets.UTF_8));
    }

    public void replaceNovel(String novelId) {
        verifyNamespace();
        var snapshot=snapshots.read(novelId);
        List<Map<String,Object>> statements=new ArrayList<>();
        statements.add(statement("MATCH (n) WHERE n.novelId=$novelId AND any(label IN labels(n) WHERE label STARTS WITH 'NF_') DETACH DELETE n",
                Map.of("novelId",novelId)));
        statements.add(statement("MERGE (n:NF_Novel {novelId:$novelId}) SET n.title=$title,n.revision=$revision,n.status=$status,n.projectKey=$projectKey",
                map("novelId",novelId,"title",snapshot.novel().get("title"),"revision",snapshot.novel().get("revision"),
                        "status",snapshot.novel().get("status"),"projectKey",projectKey)));
        statements.add(statement("MATCH (m:NF_ProjectionMeta {projectKey:$projectKey}) SET m.schemaVersion=$schemaVersion,m.updatedAt=datetime()",
                Map.of("projectKey",projectKey,"schemaVersion",PROJECTION_SCHEMA_VERSION)));
        statements.add(statement("""
                UNWIND $items AS item
                MATCH (n:NF_Novel {novelId:$novelId})
                MERGE (c:NF_Chapter {novelId:$novelId,chapterNumber:item.chapterNumber})
                SET c.title=item.title,c.summary=item.summary,c.versionId=item.versionId
                MERGE (n)-[:NF_HAS_CHAPTER]->(c)
                """,Map.of("novelId",novelId,"items",snapshot.chapters())));
        statements.add(statement("""
                UNWIND $items AS item
                MATCH (n:NF_Novel {novelId:$novelId})
                MERGE (a:NF_Artifact {novelId:$novelId,artifactId:item.artifactId})
                SET a.ordinal=item.ordinal,a.kind=item.kind,a.chapterNumber=item.chapterNumber,
                    a.batchNumber=item.batchNumber,a.approvedVersionId=item.approvedVersionId,
                    a.basisVersionId=item.basisVersionId,a.latestPlanStartChapter=item.latestPlanStartChapter,
                    a.needsRevision=item.needsRevision
                MERGE (n)-[:NF_HAS_ARTIFACT]->(a)
                """,Map.of("novelId",novelId,"items",snapshot.artifacts())));
        statements.add(statement("""
                UNWIND $items AS item
                MATCH (n:NF_Novel {novelId:$novelId})
                MATCH (a:NF_Artifact {novelId:$novelId,artifactId:item.artifactId})
                MERGE (v:NF_Version {novelId:$novelId,versionId:item.versionId})
                SET v.artifactId=item.artifactId,v.ordinal=item.ordinal,v.kind=item.kind,v.chapterNumber=item.chapterNumber,
                    v.title=item.title,v.summary=item.summary,v.dismissed=item.dismissed,
                    v.sourceVersionIds=item.sourceVersionIds,v.planStartChapter=item.planStartChapter
                MERGE (n)-[:NF_HAS_VERSION]->(v)
                MERGE (v)-[:NF_VERSION_OF_ARTIFACT]->(a)
                WITH v,item
                OPTIONAL MATCH (c:NF_Chapter {novelId:$novelId,chapterNumber:item.chapterNumber})
                FOREACH (_ IN CASE WHEN c IS NULL THEN [] ELSE [1] END | MERGE (v)-[:NF_VERSION_OF]->(c))
                """,Map.of("novelId",novelId,"items",snapshot.versions())));
        statements.add(statement("""
                UNWIND $items AS item
                MATCH (dependent:NF_Version {novelId:$novelId,versionId:item.versionId})
                MATCH (source:NF_Version {novelId:$novelId,versionId:item.sourceVersionId})
                MERGE (dependent)-[:NF_DEPENDS_ON_VERSION]->(source)
                """,Map.of("novelId",novelId,"items",snapshot.dependencies())));
        statements.add(statement("""
                UNWIND $items AS item
                MATCH (n:NF_Novel {novelId:$novelId})
                MERGE (f:NF_Fact {novelId:$novelId,factId:item.factId})
                SET f.type=item.type,f.key=item.key,f.detail=item.detail,f.state=item.state,f.chapterNumber=item.chapterNumber,f.sourceVersionId=item.sourceVersionId
                MERGE (n)-[:NF_HAS_FACT]->(f)
                WITH f,item
                OPTIONAL MATCH (v:NF_Version {novelId:$novelId,versionId:item.sourceVersionId})
                FOREACH (_ IN CASE WHEN v IS NULL THEN [] ELSE [1] END | MERGE (v)-[:NF_SOURCE_OF]->(f))
                """,Map.of("novelId",novelId,"items",snapshot.facts())));
        statements.add(statement("""
                UNWIND $items AS item
                MATCH (n:NF_Novel {novelId:$novelId})
                MERGE (e:NF_Event {novelId:$novelId,eventId:item.eventId})
                SET e.type=item.type,e.title=item.title,e.summary=item.summary,e.chapterNumber=item.chapterNumber,e.sequence=item.sequence,e.timeText=item.timeText,e.status=item.status,e.sourceVersionId=item.sourceVersionId
                MERGE (n)-[:NF_HAS_EVENT]->(e)
                WITH e,item
                OPTIONAL MATCH (c:NF_Chapter {novelId:$novelId,chapterNumber:item.chapterNumber})
                FOREACH (_ IN CASE WHEN c IS NULL THEN [] ELSE [1] END | MERGE (e)-[:NF_OCCURS_IN]->(c))
                WITH e,item
                OPTIONAL MATCH (v:NF_Version {novelId:$novelId,versionId:item.sourceVersionId})
                FOREACH (_ IN CASE WHEN v IS NULL THEN [] ELSE [1] END | MERGE (v)-[:NF_SOURCE_OF]->(e))
                """,Map.of("novelId",novelId,"items",snapshot.events())));
        statements.add(statement("""
                UNWIND $items AS item
                MATCH (n:NF_Novel {novelId:$novelId})
                MERGE (f:NF_Foreshadow {novelId:$novelId,foreshadowId:item.foreshadowId})
                SET f.title=item.title,f.description=item.description,f.plantedChapter=item.plantedChapter,
                    f.developedChaptersJson=item.developedChaptersJson,f.resolvedChapter=item.resolvedChapter,
                    f.status=item.status,f.sourceVersionId=item.sourceVersionId
                MERGE (n)-[:NF_HAS_FORESHADOW]->(f)
                WITH f,item
                OPTIONAL MATCH (v:NF_Version {novelId:$novelId,versionId:item.sourceVersionId})
                FOREACH (_ IN CASE WHEN v IS NULL THEN [] ELSE [1] END | MERGE (v)-[:NF_SOURCE_OF]->(f))
                """,Map.of("novelId",novelId,"items",snapshot.foreshadows())));
        statements.add(statement("""
                UNWIND $items AS item
                MATCH (n:NF_Novel {novelId:$novelId})
                MERGE (e:NF_Entity {novelId:$novelId,entityId:item.entityId})
                SET e.key=item.key,e.type=item.type,e.name=item.name,e.aliasesJson=item.aliasesJson,
                    e.description=item.description,e.sourceVersionId=item.sourceVersionId,
                    e.validFromChapter=item.validFromChapter,e.validToChapter=item.validToChapter,e.status=item.status
                MERGE (n)-[:NF_HAS_ENTITY]->(e)
                WITH e,item
                OPTIONAL MATCH (v:NF_Version {novelId:$novelId,versionId:item.sourceVersionId})
                FOREACH (_ IN CASE WHEN v IS NULL THEN [] ELSE [1] END | MERGE (v)-[:NF_SOURCE_OF]->(e))
                """,Map.of("novelId",novelId,"items",snapshot.entities())));
        statements.add(statement("""
                UNWIND $items AS item
                MATCH (source:NF_Entity {novelId:$novelId,entityId:item.fromEntityId})
                MATCH (target:NF_Entity {novelId:$novelId,entityId:item.toEntityId})
                MERGE (source)-[r:NF_ENTITY_RELATION {novelId:$novelId,relationId:item.relationId}]->(target)
                SET r.key=item.key,r.type=item.type,r.detail=item.detail,r.sourceVersionId=item.sourceVersionId,
                    r.validFromChapter=item.validFromChapter,r.validToChapter=item.validToChapter,r.status=item.status
                """,Map.of("novelId",novelId,"items",snapshot.relations())));
        post(statements);
    }

    private void verifyNamespace() {
        JsonNode response=post(List.of(statement("MATCH (m:NF_ProjectionMeta {projectKey:$projectKey}) RETURN count(m) AS count",
                Map.of("projectKey",projectKey))));
        int count=response.path("results").path(0).path("data").path(0).path("row").path(0).asInt(0);
        if(count!=1) throw new IllegalStateException("Neo4j 不是 NovelForge 已初始化的独立投影容器");
    }

    private JsonNode post(List<Map<String,Object>> statements) {
        try {
            String body=mapper.writeValueAsString(Map.of("statements",statements));
            HttpRequest request=HttpRequest.newBuilder(commitUri).timeout(Duration.ofSeconds(20))
                    .header("Authorization",authorization).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
            HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()<200||response.statusCode()>=300) throw new IllegalStateException("Neo4j HTTP "+response.statusCode());
            JsonNode result=mapper.readTree(response.body());
            if(result.path("errors").isArray() && !result.path("errors").isEmpty()) {
                String code=result.path("errors").path(0).path("code").asText("Neo4jError");
                String message=result.path("errors").path(0).path("message").asText("投影失败");
                throw new IllegalStateException(code+": "+message);
            }
            return result;
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Neo4j 投影被中断",e);
        } catch(java.io.IOException e) { throw new IllegalStateException("无法连接 Neo4j 投影服务",e); }
    }

    private Map<String,Object> statement(String cypher,Map<String,Object> parameters) { return map("statement",cypher,"parameters",parameters); }
    private Map<String,Object> map(Object... values) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2) result.put((String)values[i],values[i+1]);
        return result;
    }
}
