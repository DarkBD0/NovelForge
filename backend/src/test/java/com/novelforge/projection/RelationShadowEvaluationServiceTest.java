package com.novelforge.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RelationShadowEvaluationServiceTest {
    private HttpServer server;
    private JdbcTemplate jdbc;
    private volatile boolean mismatch;

    @BeforeEach void setUp() throws Exception {
        DriverManagerDataSource dataSource=new DriverManagerDataSource("jdbc:h2:mem:relation-shadow-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE canon_entity(id VARCHAR(80) PRIMARY KEY,novel_id VARCHAR(36),entity_key VARCHAR(200),
                  entity_type VARCHAR(40),canonical_name VARCHAR(300),status VARCHAR(40),source_version_id VARCHAR(36),
                  valid_from_chapter INT,valid_to_chapter INT)
                """);
        jdbc.execute("""
                CREATE TABLE entity_relation(id VARCHAR(80) PRIMARY KEY,novel_id VARCHAR(36),relation_key VARCHAR(200),
                  from_entity_id VARCHAR(80),relation_type VARCHAR(80),to_entity_id VARCHAR(80),status VARCHAR(40),
                  source_version_id VARCHAR(36),valid_from_chapter INT,valid_to_chapter INT)
                """);
        entity("ea","a","甲","v1"); entity("eb","b","乙","v1"); entity("ec","c","丙","v1");
        relation("er1","r1","ea","KNOWS","eb","ACTIVE","v2",1,null);
        relation("er2","r2","eb","LOCATED_AT","ec","ACTIVE","v3",2,null);
        relation("er3","r3","ec","ALLY","ea","ENDED","v4",1,3);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/db/neo4j/tx/commit",this::respond);
        server.start();
    }

    @AfterEach void tearDown() { if(server!=null) server.stop(0); }

    @Test void comparesTheFixedRelationQuerySetWithoutTreatingItAsImpactAnalysis() {
        var service=service(true);
        var report=service.evaluate("novel-1");

        assertThat(report.status()).isEqualTo(RelationShadowEvaluationService.Status.MATCHED);
        assertThat(report.shadowOnly()).isTrue();
        assertThat(report.scope()).isEqualTo("ENTITY_RELATIONS_ONLY");
        assertThat(report.comparisons()).extracting(RelationShadowEvaluationService.Comparison::query)
                .containsExactly("ENTITY_KEYS","ALL_RELATIONS","ACTIVE_RELATIONS","ACTIVE_TWO_HOP_PATHS");
        assertThat(report.comparisons()).allMatch(RelationShadowEvaluationService.Comparison::matched);
        assertThat(report.comparisons().get(3).mysqlCount()).isEqualTo(1);
    }

    @Test void exposesMismatchesAndDegradesWhenNeo4jIsUnavailable() {
        mismatch=true;
        var mismatchReport=service(true).evaluate("novel-1");
        assertThat(mismatchReport.status()).isEqualTo(RelationShadowEvaluationService.Status.MISMATCH);
        assertThat(mismatchReport.comparisons().get(1).missingInNeo4j()).isNotEmpty();

        server.stop(0);
        var unavailable=service(true).evaluate("novel-1");
        assertThat(unavailable.status()).isEqualTo(RelationShadowEvaluationService.Status.UNAVAILABLE);
        assertThat(unavailable.diagnostic()).contains("Neo4j");
    }

    @Test void reportsNoSamplesInsteadOfClaimingSuccessForAnEmptyNovel() {
        var report=service(true).evaluate("empty-novel");
        assertThat(report.status()).isEqualTo(RelationShadowEvaluationService.Status.NO_SAMPLES);
        assertThat(report.diagnostic()).contains("不计为效果样本");
    }

    private RelationShadowEvaluationService service(boolean enabled) {
        return new RelationShadowEvaluationService(jdbc,new ObjectMapper(),enabled,
                "http://127.0.0.1:"+server.getAddress().getPort(),"neo4j","secret","novelforge-test");
    }

    private void entity(String id,String key,String name,String version) {
        jdbc.update("INSERT INTO canon_entity VALUES(?,?,?,?,?,?,?,?,?)",id,"novel-1",key,"CHARACTER",name,"CURRENT",version,1,null);
    }
    private void relation(String id,String key,String from,String type,String to,String status,String version,int fromChapter,Integer toChapter) {
        jdbc.update("INSERT INTO entity_relation VALUES(?,?,?,?,?,?,?,?,?,?)",id,"novel-1",key,from,type,to,status,version,fromChapter,toChapter);
    }

    private void respond(HttpExchange exchange) throws java.io.IOException {
        String request=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
        boolean empty=request.contains("empty-novel");
        String relationRows=mismatch
                ? "[[\"r1\",\"a\",\"KNOWS\",\"b\",\"ACTIVE\",\"v2\",1,null],[\"r2\",\"b\",\"LOCATED_AT\",\"c\",\"ACTIVE\",\"v3\",2,null]]"
                : "[[\"r1\",\"a\",\"KNOWS\",\"b\",\"ACTIVE\",\"v2\",1,null],[\"r2\",\"b\",\"LOCATED_AT\",\"c\",\"ACTIVE\",\"v3\",2,null],[\"r3\",\"c\",\"ALLY\",\"a\",\"ENDED\",\"v4\",1,3]]";
        String response=empty?"""
                {"results":[{"data":[{"row":[3]}]},{"data":[]},{"data":[]},{"data":[]},{"data":[]}],"errors":[]}
                """:"""
                {"results":[
                  {"data":[{"row":[3]}]},
                  {"data":[{"row":["a","CHARACTER","甲","CURRENT","v1",1,null]},
                           {"row":["b","CHARACTER","乙","CURRENT","v1",1,null]},
                           {"row":["c","CHARACTER","丙","CURRENT","v1",1,null]}]},
                  {"data":RELATION_ROWS},
                  {"data":[{"row":["r1","a","KNOWS","b","ACTIVE","v2",1,null]},
                           {"row":["r2","b","LOCATED_AT","c","ACTIVE","v3",2,null]}]},
                  {"data":[{"row":["a","r1","b","r2","c"]}]}
                ],"errors":[]}
                """.replace("RELATION_ROWS",dataRows(relationRows));
        byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type","application/json");
        exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }

    private String dataRows(String rows) {
        try {
            var array=new ObjectMapper().readTree(rows); StringBuilder result=new StringBuilder("[");
            for(int i=0;i<array.size();i++) { if(i>0) result.append(','); result.append("{\"row\":").append(array.get(i)).append('}'); }
            return result.append(']').toString();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
}
