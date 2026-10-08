package com.novelforge.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.revision.ImpactAnalyzer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImpactShadowEvaluationServiceTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private HttpServer server;
    private NovelRepository repository;
    private Novel novel;
    private volatile boolean mismatch;

    @BeforeEach void setUp() throws Exception {
        novel=new Novel();
        novel.artifacts.addAll(List.of(
                artifact("outline",Novel.Kind.OUTLINE,0,"vo"),
                artifact("characters",Novel.Kind.CHARACTERS,0,"vc","vo"),
                artifact("plan",Novel.Kind.PLAN,0,"vp","vc"),
                artifact("chapter",Novel.Kind.CHAPTER,1,"vh","vp")));
        repository=mock(NovelRepository.class); when(repository.get("novel-1")).thenReturn(novel);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/db/neo4j/tx/commit",this::respond); server.start();
    }

    @AfterEach void tearDown() { if(server!=null) server.stop(0); }

    @Test void comparesEverySourceArtifactWithoutMutatingTheNovel() {
        var report=service().evaluate("novel-1");

        assertThat(report.status()).isEqualTo(ImpactShadowEvaluationService.Status.MATCHED);
        assertThat(report.scope()).isEqualTo("VERSION_DEPENDENCY_IMPACT");
        assertThat(report.explicitDependencies()).isEqualTo(3);
        assertThat(report.comparisons()).hasSize(4).allMatch(ImpactShadowEvaluationService.Comparison::matched);
        assertThat(report.comparisons().getFirst().mysqlAffected()).containsExactly("characters","plan","chapter");
        assertThat(novel.artifacts).noneMatch(item->item.needsRevision);
        assertThat(novel.changes).isEmpty();
    }

    @Test void reportsProjectionDifferencesAndConnectionFailureWithoutChangingFormalState() {
        mismatch=true;
        var different=service().evaluate("novel-1");
        assertThat(different.status()).isEqualTo(ImpactShadowEvaluationService.Status.MISMATCH);
        assertThat(different.comparisons()).anyMatch(item->!item.matched());

        server.stop(0);
        var unavailable=service().evaluate("novel-1");
        assertThat(unavailable.status()).isEqualTo(ImpactShadowEvaluationService.Status.UNAVAILABLE);
        assertThat(novel.artifacts).noneMatch(item->item.needsRevision);
    }

    private ImpactShadowEvaluationService service() {
        return new ImpactShadowEvaluationService(repository,new ImpactAnalyzer(mapper),mapper,true,
                "http://127.0.0.1:"+server.getAddress().getPort(),"neo4j","secret","novelforge-test");
    }

    private Novel.Artifact artifact(String id,Novel.Kind kind,int chapter,String versionId,String...dependencies) {
        Novel.Artifact artifact=new Novel.Artifact(); artifact.id=id; artifact.kind=kind; artifact.chapterNumber=chapter;
        Novel.Version version=new Novel.Version(); version.id=versionId; version.sourceVersionIds=List.of(dependencies);
        if(kind==Novel.Kind.PLAN) { version.plan=new Novel.Plan(); version.plan.startChapter=1; }
        artifact.versions.add(version); artifact.approvedVersionId=versionId; return artifact;
    }

    private void respond(HttpExchange exchange) throws java.io.IOException {
        exchange.getRequestBody().readAllBytes();
        String chapterDependency=mismatch?"[\"other\"]":"[\"vp\"]";
        String response="""
                {"results":[
                  {"data":[{"row":[3]}]},
                  {"data":[
                    {"row":["outline",0,"OUTLINE",0,0,"vo",false,"vo",0,false,[],null]},
                    {"row":["characters",1,"CHARACTERS",0,0,"vc",false,"vc",0,false,["vo"],null]},
                    {"row":["plan",2,"PLAN",0,0,"vp",false,"vp",0,false,["vc"],1]},
                    {"row":["chapter",3,"CHAPTER",1,0,"vh",false,"vh",0,false,CHAPTER_DEPENDENCY,null]}
                  ]}
                ],"errors":[]}
                """.replace("CHAPTER_DEPENDENCY",chapterDependency);
        byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type","application/json");
        exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
}
