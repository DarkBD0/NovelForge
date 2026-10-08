package com.novelforge.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Neo4jProjectionClientTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final List<String> requests=new ArrayList<>();
    private HttpServer server;

    @BeforeEach void startServer() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/db/neo4j/tx/commit",this::handle);
        server.start();
    }

    @AfterEach void stopServer() { server.stop(0); }

    @Test void replacesAWholeNovelWithConfirmedEntitiesAndFixedTypeRelationsIdempotently() throws Exception {
        Neo4jProjectionSnapshotReader snapshots=mock(Neo4jProjectionSnapshotReader.class);
        var snapshot=new Neo4jProjectionSnapshotReader.Snapshot(
                map("id","novel-1","title","雨夜","revision",7L,"status","WRITING"),
                List.of(),List.of(),List.of(),List.of(),List.of(),
                List.of(
                        map("entityId","entity-a","key","character_a","type","CHARACTER","name","甲","aliasesJson","[]",
                                "description","人物甲","sourceVersionId","version-2","validFromChapter",1,"validToChapter",null,"status","ACTIVE"),
                        map("entityId","entity-b","key","character_b","type","CHARACTER","name","乙","aliasesJson","[]",
                                "description","人物乙","sourceVersionId","version-2","validFromChapter",1,"validToChapter",null,"status","ACTIVE")
                ),
                List.of(map("relationId","relation-1","key","relation_a_allies_b","fromEntityId","entity-a","type","ALLY",
                        "toEntityId","entity-b","detail","同盟已经结束","sourceVersionId","version-2","validFromChapter",1,
                        "validToChapter",2,"status","ENDED")),
                List.of(map("artifactId","artifact-1","ordinal",0,"kind","CHAPTER","chapterNumber",1,"batchNumber",0,
                        "approvedVersionId","version-2","basisVersionId","version-2","latestPlanStartChapter",null,"needsRevision",false)),
                List.of(map("versionId","version-2","sourceVersionId","version-1")));
        when(snapshots.read("novel-1")).thenReturn(snapshot);
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        Neo4jProjectionClient client=new Neo4jProjectionClient(mapper,snapshots,base,"neo4j","secret","novelforge-test");

        client.replaceNovel("novel-1");
        client.replaceNovel("novel-1");

        assertThat(requests).hasSize(4);
        JsonNode firstWrite=mapper.readTree(requests.get(1));
        JsonNode secondWrite=mapper.readTree(requests.get(3));
        assertThat(firstWrite).isEqualTo(secondWrite);
        assertThat(firstWrite.path("statements").path(0).path("statement").asText()).contains("DETACH DELETE");
        assertThat(firstWrite.toString()).contains("NF_Entity","NF_HAS_ENTITY","NF_ENTITY_RELATION")
                .contains("NF_Artifact","NF_VERSION_OF_ARTIFACT","NF_DEPENDS_ON_VERSION")
                .contains("relation_a_allies_b","ENDED","validToChapter")
                .doesNotContain("ALLY]->");
        assertThat(firstWrite.toString()).contains("\"schemaVersion\":3");
    }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        String request=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
        requests.add(request);
        boolean verify=request.contains("RETURN count(m) AS count");
        String response=verify
                ? "{\"results\":[{\"data\":[{\"row\":[1]}]}],\"errors\":[]}"
                : "{\"results\":[],\"errors\":[]}";
        byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type","application/json");
        exchange.sendResponseHeaders(200,bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private Map<String,Object> map(Object... values) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2) result.put((String)values[i],values[i+1]);
        return result;
    }
}
