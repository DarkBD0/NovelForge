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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name="novelforge.projections.elasticsearch.enabled",havingValue="true")
public class ElasticsearchProjectionClient {
    public record Hit(String sourceVersionId,String artifactId,int chapterNumber,double score,String title,String summary,String excerpt) {}

    private final ObjectMapper mapper;
    private final ElasticsearchProjectionSnapshotReader snapshots;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String baseUrl;
    private final String index;

    public ElasticsearchProjectionClient(ObjectMapper mapper,ElasticsearchProjectionSnapshotReader snapshots,
                                         @Value("${novelforge.projections.elasticsearch.url}") String baseUrl,
                                         @Value("${novelforge.projections.elasticsearch.index:novelforge-local-memory}") String index) {
        URI base=URI.create(baseUrl);
        if(!List.of("http","https").contains(base.getScheme())) throw new IllegalStateException("Elasticsearch 地址必须使用 http 或 https");
        if(index==null || !index.matches("[a-z0-9][a-z0-9._-]*") || index.contains("*") || index.equals("_all")) {
            throw new IllegalStateException("Elasticsearch 索引名称不安全");
        }
        this.mapper=mapper; this.snapshots=snapshots; this.baseUrl=baseUrl.replaceAll("/+$",""); this.index=index;
    }

    public void replaceNovel(String novelId) {
        verifyIndex();
        post("/"+index+"/_delete_by_query?refresh=true&conflicts=proceed",
                Map.of("query",Map.of("term",Map.of("novelId",novelId))));
        List<Map<String,Object>> documents=snapshots.read(novelId);
        if(documents.isEmpty()) return;
        StringBuilder body=new StringBuilder();
        for(Map<String,Object> document:documents) {
            body.append(json(Map.of("index",Map.of("_index",index,"_id",document.get("id"))))).append('\n');
            body.append(json(document)).append('\n');
        }
        JsonNode result=request("/_bulk?refresh=wait_for","application/x-ndjson",body.toString());
        if(result.path("errors").asBoolean(false)) {
            JsonNode items=result.path("items");
            String reason=items.isArray()&&!items.isEmpty()?items.path(0).path("index").path("error").path("reason").asText("批量写入失败"):"批量写入失败";
            throw new IllegalStateException("Elasticsearch 批量写入失败："+reason);
        }
    }

    public List<Hit> search(String novelId,String query,int beforeChapter,int limit) {
        verifyIndex();
        Map<String,Object> body=searchBody(novelId,query,beforeChapter,limit);
        JsonNode result=post("/"+index+"/_search",body);
        List<Hit> hits=new ArrayList<>();
        for(JsonNode item:result.path("hits").path("hits")) {
            JsonNode source=item.path("_source");
            String excerpt=item.path("highlight").path("text").isArray() && !item.path("highlight").path("text").isEmpty()
                    ?item.path("highlight").path("text").path(0).asText(""):"";
            hits.add(new Hit(source.path("sourceVersionId").asText(),source.path("artifactId").asText(),
                    source.path("chapterNumber").asInt(),item.path("_score").asDouble(),source.path("title").asText(""),
                    source.path("summary").asText(""),excerpt));
        }
        return List.copyOf(hits);
    }

    Map<String,Object> searchBody(String novelId,String query,int beforeChapter,int limit) {
        Map<String,Object> chapterScope=Map.of("bool",Map.of("should",List.of(
                Map.of("term",Map.of("chunkType","CHARACTERS")),
                Map.of("range",Map.of("chapterNumber",Map.of("lt",beforeChapter)))
        ),"minimum_should_match",1));
        Map<String,Object> lexical=Map.of("bool",Map.of("should",List.of(
                Map.of("multi_match",Map.of("query",query,
                        "fields",List.of("title.cjk^8","summary.cjk^4","text.cjk"),"type","best_fields","boost",4)),
                Map.of("multi_match",Map.of("query",query,
                        "fields",List.of("title^3","summary^2","text"),"type","best_fields","boost",0.25))
        ),"minimum_should_match",1));
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("size",limit); body.put("track_total_hits",false);
        body.put("query",Map.of("bool",Map.of(
                "must",List.of(lexical),
                "should",List.of(Map.of("term",Map.of("title.exact",Map.of("value",query,"boost",20)))),
                "filter",List.of(Map.of("term",Map.of("novelId",novelId)),Map.of("term",Map.of("confirmed",true)),
                        Map.of("term",Map.of("authorityState","CONFIRMED")),chapterScope)
        )));
        body.put("highlight",Map.of("fields",Map.of("text",Map.of("fragment_size",120,"number_of_fragments",1)),
                "pre_tags",List.of(""),"post_tags",List.of("")));
        return body;
    }

    private void verifyIndex() { get("/"+index+"/_mapping"); }
    private JsonNode get(String path) { return send(HttpRequest.newBuilder(URI.create(baseUrl+path)).GET().timeout(Duration.ofSeconds(10)).build()); }
    private JsonNode post(String path,Object body) { return request(path,"application/json",json(body)); }
    private JsonNode request(String path,String contentType,String body) {
        HttpRequest request=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type",contentType).POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
        return send(request);
    }
    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()<200||response.statusCode()>=300) throw new IllegalStateException("Elasticsearch HTTP "+response.statusCode());
            return mapper.readTree(response.body());
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Elasticsearch 请求被中断",e);
        } catch(java.io.IOException e) { throw new IllegalStateException("无法连接 Elasticsearch",e); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(Exception e) { throw new IllegalStateException("无法序列化 Elasticsearch 请求",e); }
    }
}
