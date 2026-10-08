package com.novelforge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.generation.ModelGateway;
import com.novelforge.generation.ModelGateway.Generated;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.outline.OutlineRenderer;
import com.novelforge.outline.OutlineStructureService;
import com.novelforge.task.TaskService;
import com.novelforge.workflow.WorkflowService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP model adapter + API/controller/task/transaction path, with no external API or user DB. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:http-rewrite;DB_CLOSE_DELAY=-1",
        "novelforge.model.mode=http", "novelforge.model.name=fixture", "novelforge.model.api-key=",
        "novelforge.model.response-format=json_object", "novelforge.model.timeout-seconds=5"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class HttpRewriteIntegrationTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final ConcurrentLinkedQueue<String> replies=new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<JsonNode> requests=new ConcurrentLinkedQueue<>();
    private static final HttpServer server=startServer();
    @Autowired MockMvc mvc;
    @Autowired NovelRepository repository;
    @Autowired WorkflowService workflow;
    @Autowired TaskService tasks;
    @Autowired OutlineRenderer outlineRenderer;
    @Autowired OutlineStructureService outlineStructure;

    private static HttpServer startServer() {
        try {
            HttpServer s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            s.createContext("/v1/chat/completions", exchange->{
                requests.add(JSON.readTree(exchange.getRequestBody()));
                String reply=replies.poll();
                byte[] bytes=(reply==null ? "{}" : reply).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.sendResponseHeaders(reply==null ? 500 : 200,bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            s.start(); return s;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void modelAddress(DynamicPropertyRegistry properties) {
        properties.add("novelforge.model.base-url",()->"http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
    }
    @BeforeEach void resetFixtures() { replies.clear(); requests.clear(); }
    @AfterAll static void closeServer() { server.stop(0); }

    Novel seed() {
        Novel n=workflow.create("修订验证","只使用隔离测试数据",1000,"");
        return repository.update(n.id,x->{
            x.autoStyleEnabled=false;
            Artifact outline=new Artifact(); outline.kind=Kind.OUTLINE;
            Version old=new Version(); old.title="旧稿"; old.content="过期历史文本"; old.summary="旧摘要";
            var spec=OutlineTestFixtures.validSpec(1000,"原有主线和明确结局");
            Version current=new Version(); current.title="全书大纲"; current.content=outlineRenderer.render(spec); current.summary="当前摘要";
            current.outlineSpec=spec; current.outlineSpecHash=outlineStructure.hash(spec);
            outline.versions.addAll(List.of(old,current)); outline.approvedVersionId=current.id;
            Artifact characters=new Artifact(); characters.kind=Kind.CHARACTERS;
            Version setting=new Version(); setting.title="人物设定"; setting.content="原有人物动机"; setting.summary="人物摘要";
            characters.versions.add(setting); characters.approvedVersionId=setting.id;
            x.artifacts.addAll(List.of(outline,characters)); return x;
        });
    }
    void reply(String text) throws Exception {
        replies.add(JSON.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",text))))));
    }
    void candidate() throws Exception {
        reply(JSON.writeValueAsString(OutlineTestFixtures.generated(1000,"修订后主线、人物动机与明确结局","修订摘要")));
    }
    Task rewrite(Novel n) throws Exception {
        return submit(n,Action.REWRITE,n.artifacts.getFirst().id,"增强人物动机，保留明确结局");
    }
    Task submit(Novel n,Action action,String target,String instructions) throws Exception {
        var payload=new LinkedHashMap<String,Object>();
        payload.put("action",action); payload.put("artifactId",target); payload.put("instructions",instructions);
        payload.put("requestKey",Novel.uid()); payload.put("revision",n.revision);
        String response=mvc.perform(post("/api/novels/{id}/generation-tasks",n.id)
                .header("X-NovelForge-Request","1").contentType("application/json")
                .content(JSON.writeValueAsString(payload)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String taskId=JSON.readTree(response).path("id").asText();
        await().atMost(Duration.ofSeconds(10)).until(()->{
            TaskStatus state=tasks.find(repository.get(n.id),taskId).status;
            return state!=TaskStatus.RUNNING && state!=TaskStatus.QUEUED;
        });
        return tasks.find(repository.get(n.id),taskId);
    }
    @Test void rewriteCreatesCandidateThenStillRequiresHumanConfirmation() throws Exception {
        Novel before=seed(); candidate();
        reply("检查结果如下：\n```JSON\n"+JSON.writeValueAsString(new Review(true,List.of(),false,false,false))+"\n```");
        reply(JSON.writeValueAsString(new Review(true,List.of(),true,true,true)));
        Task task=rewrite(before);
        assertThat(task.status).as(task.error).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(requests).hasSize(3);
        JsonNode body=requests.peek();
        JsonNode input=JSON.readTree(body.path("messages").get(1).path("content").asText());
        assertThat(input.path("instructions").asText()).isEqualTo("增强人物动机，保留明确结局");
        JsonNode target=input.path("context").path("revisionTarget");
        assertThat(target.path("kind").asText()).isEqualTo("OUTLINE");
        assertThat(target.path("content").asText()).isEqualTo(before.artifacts.getFirst().latest().content);
        assertThat(target.path("versionId").asText()).isEqualTo(before.artifacts.getFirst().latest().id);
        assertThat(target.has("versions")).isFalse();
        assertThat(input.toString()).doesNotContain("过期历史文本");
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        Novel after=repository.get(before.id); Artifact outline=after.artifacts.getFirst();
        assertThat(outline.versions).hasSize(3);
        assertThat(outline.approvedVersionId).isEqualTo(before.artifacts.getFirst().approvedVersionId);
        assertThat(outline.approved().content).isEqualTo(before.artifacts.getFirst().approved().content);
        assertThat(outline.latest().baseVersionId).isEqualTo(before.artifacts.getFirst().latest().id);
        assertThat(outline.latest().content).contains("修订后主线、人物动机与明确结局");
        assertThat(outline.latest().outlineSpecHash).hasSize(64);
        assertThat(outline.clean()).isFalse();
        assertThat(after.artifacts.getLast().needsRevision).isTrue();
        workflow.confirm(after.id,outline.id,outline.latest().id,after.revision,null);
        assertThat(repository.get(after.id).artifacts.getFirst().clean()).isTrue();
    }
    @Test void structuredOutlineEditEndpointSavesRenderedCandidateBeforeAnyPaidReview() throws Exception {
        Novel before=seed(); Artifact outline=before.artifacts.getFirst();
        var spec=OutlineTestFixtures.validSpec(1000,"作者手动调整后的主线和明确结局");
        spec.parts.getFirst().stages.getFirst().events.getFirst().consequence="证据链发生新的后果";
        var payload=new LinkedHashMap<String,Object>();
        payload.put("baseVersionId",outline.latest().id); payload.put("title","作者编辑的大纲");
        payload.put("summary","作者编辑后的摘要"); payload.put("outlineSpec",spec);
        payload.put("reason","调整关键事件后果"); payload.put("revision",before.revision);

        mvc.perform(post("/api/novels/{id}/artifacts/{artifactId}/outline-versions",before.id,outline.id)
                        .header("X-NovelForge-Request","1").contentType("application/json")
                        .content(JSON.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.novel.revision").value(before.revision+1));

        Novel saved=repository.get(before.id); Version candidate=saved.artifacts.getFirst().latest();
        assertThat(saved.artifacts.getFirst().versions).hasSize(3);
        assertThat(candidate.baseVersionId).isEqualTo(outline.latest().id);
        assertThat(candidate.source).isEqualTo("USER");
        assertThat(candidate.content).isEqualTo(outlineRenderer.render(spec)).contains("证据链发生新的后果");
        assertThat(candidate.outlineSpecHash).isEqualTo(outlineStructure.hash(spec));
        assertThat(candidate.review.passed()).isTrue();
        assertThat(candidate.reviewPolicyVersion).isEqualTo(OutlineStructureService.VERSION);
        assertThat(saved.artifacts.getLast().needsRevision).isTrue();
        assertThat(requests).isEmpty();
    }
    @Test void plainTextRewriteFailsWithoutChangingVersionsOrDownstreamOrRetrying() throws Exception {
        Novel before=seed(); reply("我已经修改了人物动机，下面是纯正文。");
        Task task=rewrite(before);
        assertThat(task.status).isEqualTo(TaskStatus.FAILED);
        assertThat(task.error).contains("写作生成失败","普通文本");
        assertUnchanged(before); assertThat(requests).hasSize(1);
    }
    @Test void malformedReviewPreservesGeneratedCandidateAndIdentifiesFailedStage() throws Exception {
        Novel before=seed(); candidate(); reply("审核通过，没有问题。");
        Task task=rewrite(before);
        assertThat(task.status).isEqualTo(TaskStatus.FAILED);
        assertThat(task.error).contains("大纲连贯性检查失败","候选内容已保存","不需要重新生成正文");
        assertStagedCandidate(before,task,Action.REWRITE); assertThat(requests).hasSize(2);
    }
    @Test void noOpModelRewriteDoesNotCreateVersionOrInvalidateFollowingContent() throws Exception {
        Novel before=seed();
        reply(JSON.writeValueAsString(OutlineTestFixtures.generated(1000,"原有主线和明确结局","当前摘要")));
        Task task=rewrite(before);
        assertThat(task.status).isEqualTo(TaskStatus.FAILED);
        assertThat(task.error).contains("没有产生实际修改");
        assertUnchanged(before);assertThat(requests).hasSize(1);
    }
    @Test void characterRewriteThatStillContainsStoryOrderFailsWithReadableLocationAndKeepsOriginal() throws Exception {
        Novel before=seed();
        before=repository.update(before.id,n->{
            n.artifacts.get(1).latest().facts=List.of(new Fact("character_wang_ayi","CHARACTER",
                    "王阿姨是邱天初次尝试透视窥视的对象","ACTIVE"));
            return n;
        });
        reply(JSON.writeValueAsString(Map.of("operations",List.of(
                Map.of("op","REPLACE_TEXT","field","content","oldText","原有人物动机","newText","人物正文已有少量调整"),
                Map.of("op","UPSERT_FACT","fact",Map.of("key","character_wang_ayi","type","CHARACTER",
                        "detail","王阿姨是邱天初次尝试透视窥视的对象","state","ACTIVE"))))));
        reply(JSON.writeValueAsString(new Review(true,List.of(),false,false,false)));
        Artifact characters=before.artifacts.get(1);
        Task task=submit(before,Action.REWRITE,characters.id,"删除王阿姨档案中的‘初次’，只保留静态人物设定");
        assertThat(task.status).isEqualTo(TaskStatus.FAILED);
        assertThat(task.error).contains("档案条目“王阿姨是邱天初次尝试透视窥视的对象”",
                "剧情顺序词“初次”","本次修订未保存").doesNotContain("character_wang_ayi");
        assertUnchanged(before);
        assertThat(requests).hasSize(1);
    }
    void assertUnchanged(Novel before) throws Exception {
        Novel after=repository.get(before.id);
        assertThat(after.revision).isEqualTo(before.revision);
        assertThat(after.changes).isEmpty();
        assertThat(JSON.writeValueAsString(after.artifacts)).isEqualTo(JSON.writeValueAsString(before.artifacts));
    }
    void assertStagedCandidate(Novel before,Task task,Action action) {
        Novel after=repository.get(before.id);
        assertThat(after.revision).isEqualTo(before.revision+1);
        assertThat(task.resultArtifactId).isNotBlank();
        assertThat(task.resultVersionId).isNotBlank();
        Artifact candidate=after.artifacts.stream().filter(a->a.id.equals(task.resultArtifactId)).findFirst().orElseThrow();
        assertThat(candidate.latest().id).isEqualTo(task.resultVersionId);
        assertThat(candidate.latest().review).isNull();
        if (action==Action.REWRITE) {
            Artifact original=before.artifacts.stream().filter(a->a.id.equals(candidate.id)).findFirst().orElseThrow();
            assertThat(after.artifacts).hasSize(before.artifacts.size());
            assertThat(candidate.versions).hasSize(original.versions.size()+1);
            assertThat(candidate.approvedVersionId).isEqualTo(original.approvedVersionId);
            assertThat(candidate.approved().content).isEqualTo(original.approved().content);
        } else {
            assertThat(after.artifacts).hasSize(before.artifacts.size()+1);
            assertThat(candidate.approvedVersionId).isNull();
        }
    }
    Plan finalPlan() {
        Plan plan=new Plan(); plan.startChapter=1; plan.endChapter=1; plan.prepareNextAfterChapter=1; plan.finalBatch=true;
        plan.triggerReason="本批结尾收束故事"; plan.handoff="故事明确结局"; plan.assumptions="";
        plan.chapters=List.of(new ChapterBeat(1,"结局","回收伏笔，解决主线")); return plan;
    }
    Generated generated(Action action) {
        if (action==Action.OUTLINE)
            return OutlineTestFixtures.generated(1000,"完整的测试主线与明确结局","准确摘要");
        return new Generated("测试内容",action==Action.CHAPTER ? "终".repeat(1050) : "完整的测试内容","准确摘要",List.of(),action==Action.PLAN ? finalPlan() : null);
    }
    Artifact approved(Kind kind,Generated value) {
        Artifact a=new Artifact(); a.kind=kind; a.batchNumber=kind==Kind.PLAN ? 1 : 0; a.chapterNumber=kind==Kind.CHAPTER ? 1 : 0;
        Version v=new Version(); v.title=value.title(); v.content=value.content(); v.summary=value.summary(); v.plan=value.plan();
        v.outlineSpec=value.outlineSpec(); v.outlineSpecHash=value.outlineSpec()==null?null:outlineStructure.hash(value.outlineSpec());
        a.versions.add(v); a.approvedVersionId=v.id; return a;
    }
    Novel seedFor(Action action) {
        if (action==Action.OUTLINE) {
            Novel novel=workflow.create("接口验证","隔离测试",1000,"");
            return repository.update(novel.id,current->{ current.autoStyleEnabled=false; return current; });
        }
        Novel n=seed();
        return repository.update(n.id,x->{
            if (action==Action.CHARACTERS) x.artifacts.removeLast();
            if (action==Action.REVIEW) x.artifacts.getFirst().approvedVersionId=null;
            if (action==Action.CHAPTER || action==Action.COMPLETE) x.artifacts.add(approved(Kind.PLAN,generated(Action.PLAN)));
            if (action==Action.COMPLETE) x.artifacts.add(approved(Kind.CHAPTER,generated(Action.CHAPTER)));
            return x;
        });
    }
    @ParameterizedTest @EnumSource(value=Action.class,names="STYLE_REVIEW",mode=EnumSource.Mode.EXCLUDE)
    void reviewFailurePreservesGeneratedCandidateForEveryAction(Action action) throws Exception {
        Novel before=seedFor(action);
        boolean reviewOnly=action==Action.REVIEW || action==Action.COMPLETE;
        if (!reviewOnly) {
            if (action==Action.REWRITE) reply(JSON.writeValueAsString(
                    OutlineTestFixtures.generated(1000,"修订后的主线与明确结局","修订后的准确摘要")));
            else {
                if (action==Action.OUTLINE) reply(JSON.writeValueAsString(generated(Action.CHARACTERS)));
                reply(JSON.writeValueAsString(generated(action)));
            }
        }
        replies.add("<html>gateway error with private-payload</html>");
        String target=action==Action.REVIEW || action==Action.REWRITE ? before.artifacts.getFirst().id : null;
        Task result=submit(before,action,target,"请求要求");
        assertThat(result.status).isEqualTo(TaskStatus.FAILED);
        assertThat(result.error).contains("UPSTREAM_HTML","诊断=").doesNotContain("private-payload");
        if (reviewOnly) assertUnchanged(before);
        else {
            assertThat(result.error).contains("候选内容已保存","不需要重新生成正文");
            assertStagedCandidate(before,result,action);
        }
        assertThat(repository.get(before.id).completionChecks).isEmpty();
        assertThat(requests).hasSize(action==Action.OUTLINE ? 3 : reviewOnly ? 1 : 2);
        JsonNode input=JSON.readTree(requests.stream().toList().getLast().path("messages").get(1).path("content").asText());
        assertThat(input.path("action").asText()).isEqualTo(action==Action.COMPLETE ? "COMPLETE" : "REVIEW");
        assertThat(input.path("sourceAction").asText()).isEqualTo(action.name());
    }
    @Test void allGenerationKindsRejectUnchangedRecheckAndCompletionUsesSharedHttpAdapter() throws Exception {
        Novel n=workflow.create("全接口闭环","本机模拟模型，不代表文学质量",1000,"");
        n=repository.update(n.id,current->{ current.autoStyleEnabled=false; return current; });
        for (Action action : List.of(Action.OUTLINE,Action.CHARACTERS,Action.PLAN,Action.CHAPTER)) {
            if (action==Action.OUTLINE) reply(JSON.writeValueAsString(generated(Action.CHARACTERS)));
            reply(JSON.writeValueAsString(generated(action)));
            reply(JSON.writeValueAsString(new Review(true,List.of(),false,false,false)));
            if (action==Action.OUTLINE) reply(JSON.writeValueAsString(new Review(true,List.of(),true,true,true)));
            if (action==Action.CHAPTER) reply(JSON.writeValueAsString(new ModelGateway.StateExtraction(List.of(
                    new ModelGateway.ExtractedState("event_final","EVENT","本章进入结局","ACTIVE",
                            List.of("终终终终终终终终终终"))))));
            Task result=submit(n,action,null,"");
            assertThat(result.status).as(result.error).isEqualTo(TaskStatus.SUCCEEDED);
            n=repository.get(n.id); Artifact a=n.artifacts.getLast();
            int requestCount=requests.size();
            Novel unchanged=n;
            assertThatThrownBy(()->tasks.submit(unchanged.id,Action.REVIEW,a.id,"",Novel.uid(),unchanged.revision))
                    .hasMessageContaining("均未发生变化","不要重复调用模型");
            assertThat(requests).hasSize(requestCount);
            assertThat(repository.get(n.id).artifacts.getLast().versions).hasSize(1);
            n=workflow.confirm(n.id,a.id,a.latest().id,n.revision,null);
        }
        reply(JSON.writeValueAsString(new Review(true,List.of(),true,true,true)));
        Task result=submit(n,Action.COMPLETE,null,"");
        assertThat(result.status).as(result.error).isEqualTo(TaskStatus.SUCCEEDED);
        JsonNode input=JSON.readTree(requests.stream().toList().getLast().path("messages").get(1).path("content").asText());
        assertThat(input.path("action").asText()).isEqualTo("COMPLETE");
        assertThat(input.path("candidate").isNull()).isTrue();
        Novel checked=repository.get(n.id);
        assertThat(checked.status).isEqualTo("WRITING");
        assertThat(workflow.finish(n.id,checked.completionChecks.getLast().id(),checked.revision,true).status).isEqualTo("COMPLETED");
        assertThat(requests).hasSize(12);
    }
}
