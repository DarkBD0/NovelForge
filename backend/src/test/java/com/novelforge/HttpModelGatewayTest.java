package com.novelforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.novelforge.generation.*;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class HttpModelGatewayTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> response=new AtomicReference<>();
    private final ConcurrentLinkedQueue<String> queuedResponses=new ConcurrentLinkedQueue<>();
    private final AtomicReference<String> received=new AtomicReference<>();
    private final AtomicReference<String> authorization=new AtomicReference<>();
    private final AtomicInteger calls=new AtomicInteger();
    private final AtomicInteger statusCode=new AtomicInteger(200);
    private final AtomicReference<String> contentType=new AtomicReference<>("application/json");
    private boolean gzip;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions", exchange->{
            calls.incrementAndGet();
            received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String next=queuedResponses.poll();
            byte[] bytes=(next==null?response.get():next).getBytes(StandardCharsets.UTF_8);
            if (gzip) {
                var compressed=new ByteArrayOutputStream();
                try (var stream=new GZIPOutputStream(compressed)) { stream.write(bytes); }
                bytes=compressed.toByteArray(); exchange.getResponseHeaders().add("Content-Encoding","gzip");
            }
            exchange.getResponseHeaders().add("Content-Type",contentType.get());
            exchange.sendResponseHeaders(statusCode.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();baseUrl="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
    }
    @AfterEach void cleanup() { server.stop(0); }
    ModelGateway.Request request() {
        Novel n=new Novel();n.title="接口验证";
        return new ModelGateway.Request(Action.OUTLINE,n,null,new ContextAssembler.Context("{\"title\":\"接口验证\"}",List.of(),1,1),"");
    }
    ModelGateway.Request rewriteRequest() {
        Novel n=new Novel();n.title="修订验证";
        Artifact target=new Artifact();target.kind=Kind.CHARACTERS;
        Version version=new Version();version.title="人物设定";version.content="王阿姨是邻居。";version.summary="人物摘要";
        version.facts=List.of(new Fact("character_wang_ayi","CHARACTER","王阿姨是邱天初次窥视的对象","ACTIVE"));
        target.versions.add(version);n.artifacts.add(target);
        String context="{\"revisionTarget\":{\"kind\":\"CHARACTERS\",\"title\":\"人物设定\",\"content\":\"王阿姨是邻居。\",\"summary\":\"人物摘要\",\"facts\":[{\"key\":\"character_wang_ayi\",\"type\":\"CHARACTER\",\"detail\":\"王阿姨是邱天初次窥视的对象\",\"state\":\"ACTIVE\"}],\"plan\":null}}";
        return new ModelGateway.Request(Action.REWRITE,n,target,new ContextAssembler.Context(context,List.of(version.id),1,1),"删除王阿姨档案中的初次");
    }
    ModelGateway.Request chapterRequest() {
        Novel n=new Novel();n.title="章节验证";
        return new ModelGateway.Request(Action.CHAPTER,n,null,
                new ContextAssembler.Context("{\"title\":\"章节验证\",\"nextChapter\":1}",List.of(),1,1),"写第一章");
    }
    ModelGateway.Request styleReviewRequest() {
        Novel n=new Novel();n.title="文风验证";
        Artifact target=new Artifact();target.kind=Kind.CHAPTER;target.chapterNumber=1;
        Version version=new Version();version.title="第一章";version.content="她摇头说不记得。她真的想不起来，不是回避，不是假装。";
        version.summary="人物忘记了一个画面";target.versions.add(version);n.artifacts.add(target);
        String context="{\"revisionTarget\":{\"kind\":\"CHAPTER\",\"chapterNumber\":1,\"title\":\"第一章\",\"content\":\"她摇头说不记得。她真的想不起来，不是回避，不是假装。\",\"summary\":\"人物忘记了一个画面\",\"facts\":[],\"plan\":null}}";
        return new ModelGateway.Request(Action.STYLE_REVIEW,n,target,
                new ContextAssembler.Context(context,List.of(version.id),1,1),"");
    }
    ModelGateway.Request planRequest() {
        Novel n=new Novel();n.title="规划验证";
        return new ModelGateway.Request(Action.PLAN,n,null,
                new ContextAssembler.Context("{\"title\":\"规划验证\",\"nextChapter\":1,\"nextBatch\":1}",List.of(),1,1),"");
    }
    ModelGateway.Request chapterRewriteRequest() {
        Novel n=new Novel();n.title="章节重写验证";
        Artifact target=new Artifact();target.kind=Kind.CHAPTER;target.chapterNumber=3;
        Version version=new Version();version.title="第三章";version.content="遗漏主事件的旧正文";version.summary="旧摘要";
        version.facts=List.of(new Fact("old_event","EVENT","旧事件","ACTIVE"));target.versions.add(version);n.artifacts.add(target);
        String context="{\"currentChapterPlan\":{\"number\":3,\"purpose\":\"勒索姚远致其自杀\",\"acceptanceItems\":[\"勒索姚远致其自杀\"]},\"revisionTarget\":{\"kind\":\"CHAPTER\",\"title\":\"第三章\",\"content\":\"遗漏主事件的旧正文\",\"summary\":\"旧摘要\",\"facts\":[],\"plan\":null}}";
        return new ModelGateway.Request(Action.REWRITE,n,target,new ContextAssembler.Context(context,List.of(version.id),3,1),"整章重写并补齐所有主事件");
    }
    ModelGateway.Request localStyleRewriteRequest() {
        Novel n=new Novel();n.title="局部文风修改";
        Artifact target=new Artifact();target.kind=Kind.CHAPTER;target.chapterNumber=1;
        Version version=new Version();version.title="第一章";
        version.content="她摇了摇头。她真的想不起来，不是回避，不是假装。然后她推门离开。";
        version.summary="她否认记得画面，随后离开";
        version.facts=List.of(new Fact("event_leave","EVENT","她随后离开房间","ACTIVE"));
        target.versions.add(version);n.artifacts.add(target);
        String context="{\"revisionTarget\":{\"kind\":\"CHAPTER\",\"chapterNumber\":1,\"title\":\"第一章\",\"content\":\"她摇了摇头。她真的想不起来，不是回避，不是假装。然后她推门离开。\",\"summary\":\"她否认记得画面，随后离开\",\"facts\":[{\"key\":\"event_leave\",\"type\":\"EVENT\",\"detail\":\"她随后离开房间\",\"state\":\"ACTIVE\"}],\"plan\":null}}";
        return new ModelGateway.Request(Action.REWRITE,n,target,
                new ContextAssembler.Context(context,List.of(version.id),1,1),
                StylePatchGuard.INSTRUCTION_MARKER+"只删除重复解释句，不能返回整篇正文，不改变摘要和档案");
    }
    Map<String,Object> factPatch(String detail) {
        return Map.of("operations",List.of(Map.of("op","UPSERT_FACT","fact",Map.of("key","character_wang_ayi",
                "type","CHARACTER","detail",detail,"state","ACTIVE"))));
    }
    void reply(Object value,String finish) throws Exception {
        replyText(mapper.writeValueAsString(value),finish);
    }
    void replyText(String text,String finish) throws Exception {
        replyMessage(Map.of("content",text),finish);
    }
    void replyMessage(Map<String,Object> message,String finish) throws Exception {
        response.set(mapper.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason",finish,"message",message)))));
    }
    void queueReply(Object value) throws Exception {
        String text=mapper.writeValueAsString(value);
        queuedResponses.add(mapper.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",text))))));
    }
    HttpModelGateway gateway() { return new HttpModelGateway(mapper,baseUrl,"","fixture",5,1000,"max_tokens","none"); }
    ModelGateway.Generated generated() {
        return new ModelGateway.Generated("大纲","他说：\"线索{甲}[乙]\"。\n路径 C:\\book；结局明确。","摘要",List.of(new Fact("clue","FORESHADOW","线索已揭示","RESOLVED")),null);
    }
    @Test void sendsCompatibleRequestAndParsesGenerationAndReview() throws Exception {
        var gateway=new HttpModelGateway(mapper,baseUrl,"test-only-key","fixture",5,1000,"max_completion_tokens","none");
        reply(new ModelGateway.Generated("大纲","内容","摘要",List.of(),null),"stop");
        assertThat(gateway.generate(request()).content()).isEqualTo("内容");
        assertThat(authorization.get()).isEqualTo("Bearer test-only-key");
        assertThat(mapper.readTree(received.get()).path("max_completion_tokens").asInt()).isEqualTo(1000);
        assertThat(mapper.readTree(received.get()).path("messages").size()).isEqualTo(2);
        assertThat(mapper.readTree(received.get()).has("response_format")).isFalse();
        assertThat(mapper.readTree(received.get()).path("messages").path(0).path("content").asText())
                .contains("不得把正文剧情顺序重复写进人物档案", "同一事实只能有一个权威条目",
                        "必须逐条扫描返回的 facts 并真正删除或改写这些表述");
        reply(new Review(true,List.of(),true,true,true),"stop");
        assertThat(gateway.review(request(),null).endingClear()).isTrue();
        assertThat(mapper.readTree(received.get()).path("messages").path(0).path("content").asText())
                .contains("直接展示给中文小说作者", "禁止在 issues 中输出 candidate.facts",
                        "同一事件使用同义表达","不要要求摘要、伏笔清单和详细正文逐字一致",
                        "忽略同一句后半段","满足其中一个选项即为满足",
                        "初体验","不得强迫本章明确命名","正文比规划更具体");
    }
    @Test void refusesTruncatedOrInvalidOutputWithoutFallingBackToDemo() throws Exception {
        var gateway=gateway();
        reply(Map.of("content","不完整"),"length");
        assertThatThrownBy(()->gateway.generate(request())).hasMessageContaining("截断");
        response.set("{invalid}");
        assertThatThrownBy(()->gateway.generate(request())).hasMessageContaining("解析失败");
    }
    @Test void rewriteUsesSmallVerifiedPatchInsteadOfReturningWholeArtifact() throws Exception {
        reply(factPatch("王阿姨是宿舍管理员，习惯把现金藏在家中"),"stop");
        ModelGateway.Generated result=gateway().generate(rewriteRequest());
        assertThat(result.content()).isEqualTo("王阿姨是邻居。");
        assertThat(result.facts()).singleElement().satisfies(f->assertThat(f.detail()).doesNotContain("初次"));
        JsonNode body=mapper.readTree(received.get());
        assertThat(body.path("messages").get(0).path("content").asText()).contains("禁止返回整份","UPSERT_FACT","SET_CONTENT","REPLACE_FACTS");
        assertThat(body.path("messages").get(1).path("content").asText()).contains("revisionTarget","删除王阿姨档案中的初次");

        reply(Map.of("operations",List.of(Map.of("op","REPLACE_TEXT","field","content","oldText","不存在的旧句","newText","新句"))),"stop");
        assertThatThrownBy(()->gateway().generate(rewriteRequest())).hasMessageContaining("不存在","无法安全应用","原有内容未覆盖");
    }
    @Test void missingConfigurationAndInsecureRemoteEndpointsFailClearly() {
        var missing=new HttpModelGateway(mapper,"","","",5,1000,"max_tokens","none");
        assertThat(missing.ready()).isFalse();
        assertThatThrownBy(()->missing.generate(request())).hasMessageContaining("配置");
        var insecure=new HttpModelGateway(mapper,"http://example.com/v1","secret","fixture",5,1000,"max_tokens","none");
        assertThatThrownBy(()->insecure.generate(request())).hasMessageContaining("HTTPS");
    }
    @Test void acceptsExplanationFencesBomAndEscapedBracesWithoutChangingContent() throws Exception {
        String json=mapper.writeValueAsString(generated());
        for (String text : List.of(json,"\uFEFF"+json,"```JSON\n"+json+"\n```","已按要求修订，完整版本如下：\n```json\n"+json+"\n```\n以上是完整版本。")) {
            replyText(text,"stop");
            assertThat(gateway().generate(request())).isEqualTo(generated());
        }
    }
    @Test void onlyUsesFinalAnswerAfterCompleteThinkingWrapper() throws Exception {
        String json=mapper.writeValueAsString(generated());
        replyText("<think>此处是未提交草案：{\"content\":\"草案\"}</think>\n"+json,"stop");
        assertThat(gateway().generate(request())).isEqualTo(generated());
        replyText("<think>"+json,"stop");
        assertThatThrownBy(()->gateway().generate(request())).hasMessageContaining("未结束的思考段");
        replyMessage(Map.of("reasoning_content",json),"stop");
        assertThatThrownBy(()->gateway().generate(request())).hasMessageContaining("EMPTY_FINAL_CONTENT").hasMessageContaining("含推理=true");
    }
    @Test void readsTextBlocksFromCompatibleProvider() throws Exception {
        String json=mapper.writeValueAsString(generated());
        replyMessage(Map.of("content",List.of(Map.of("type","text","text",json.substring(0,20)),Map.of("type","text","text",json.substring(20)))),"stop");
        assertThat(gateway().generate(request())).isEqualTo(generated());
    }
    @Test void reportsRefusalEmptyContentWrongEnvelopeAndPlainTextSeparately() throws Exception {
        replyMessage(Map.of("refusal","request declined"),"stop");
        assertThatThrownBy(()->gateway().generate(request())).hasMessageContaining("模型拒绝");
        replyText("","content_filter");
        assertThatThrownBy(()->gateway().generate(request())).hasMessageContaining("拦截");
        replyText("","stop");
        assertThatThrownBy(()->gateway().generate(request())).hasMessageContaining("content 为空");
        response.set("{\"output\":[]}");
        assertThatThrownBy(()->gateway().generate(request())).hasMessageContaining("choices[0].message");
        replyText("我已经修改了主人公的动机。","stop");
        assertThatThrownBy(()->gateway().generate(request())).hasMessageContaining("写作生成失败").hasMessageContaining("普通文本");
        assertThat(calls.get()).isEqualTo(5); // No hidden retry/repair requests.
    }
    @Test void doesNotSalvageAmbiguousNestedTruncatedOrMalformedJson() throws Exception {
        String json=mapper.writeValueAsString(generated());
        for (String text : List.of(json+"\n"+json,"["+json+"]","{\"candidate\":"+json+"}",json.substring(0,json.length()-1),"{\"broken\": nope}","{\"title\":\"old\","+json.substring(1))) {
            replyText(text,"stop");
            assertThatThrownBy(()->gateway().generate(request())).hasMessageContaining("原有内容未覆盖");
        }
    }
    @Test void validatesRequiredFieldsDefaultsMissingCompletionFlagsAndNeverCoercesBooleans() throws Exception {
        reply(Map.of("content","只有正文"),"stop");
        assertThatThrownBy(()->gateway().generate(request())).hasMessageContaining("必要字段");
        reply(Map.of("passed",true,"issues",List.of()),"stop");
        Review withoutCompletionFlags=gateway().review(request(),generated());
        assertThat(withoutCompletionFlags.passed()).isTrue();
        assertThat(withoutCompletionFlags.mainlineResolved()).isFalse();
        assertThat(withoutCompletionFlags.endingClear()).isFalse();
        assertThat(withoutCompletionFlags.foreshadowingResolved()).isFalse();
        reply(Map.of("passed",true,"issues",List.of(),"mainlineResolved","false"),"stop");
        assertThatThrownBy(()->gateway().review(request(),generated())).hasMessageContaining("mainlineResolved 必须是布尔值");
        reply(Map.of("passed","true","issues",List.of(),"mainlineResolved",true,"endingClear",true,"foreshadowingResolved",true),"stop");
        assertThatThrownBy(()->gateway().review(request(),generated())).hasMessageContaining("passed 必须是布尔值");
    }
    @Test void preservesUsableGeneratedContentWhenOnlySummaryIsMissing() throws Exception {
        var output=mapper.createObjectNode(); output.put("title","第二章"); output.put("content","已经生成且应当保留的正文");
        output.putArray("facts"); output.putNull("plan"); reply(output,"stop");
        ModelGateway.Generated generated=gateway().generate(chapterRequest());
        assertThat(generated.content()).isEqualTo("已经生成且应当保留的正文");
        assertThat(generated.summary()).isEmpty();
        assertThat(generated.draftIssues()).containsExactly("内容摘要缺失或为空，请补全摘要后重新检查");
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void preservesUsableGeneratedContentWhenFactsAreMissing() throws Exception {
        var output=mapper.createObjectNode(); output.put("title","第一章");
        output.put("content","已经生成且应当保留的第一章正文"); output.put("summary","第一章摘要");
        reply(output,"stop");
        ModelGateway.Generated generated=gateway().generate(chapterRequest());
        assertThat(generated.content()).isEqualTo("已经生成且应当保留的第一章正文");
        assertThat(generated.facts()).isEmpty();
        assertThat(generated.plan()).isNull();
        assertThat(generated.draftIssues()).containsExactly("档案增量缺失，请补充本次内容已经发生的事实变化后重新检查");
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void parsesStructuredReaderFacingReviewIssues() throws Exception {
        var issue=Map.of("location","人物设定 > 王阿姨 > 档案描述","problem","把王阿姨写成第一次窥视对象",
                "evidence","已确认大纲第一章先写张洋","suggestion","删除“初次”，改为后续窥探对象","severity","必须修正");
        reply(Map.of("passed",false,"issues",List.of(issue),"mainlineResolved",false,"endingClear",false,"foreshadowingResolved",false),"stop");
        Review review=gateway().review(request(),generated());
        assertThat(review.passed()).isFalse();
        assertThat(review.issueDetails()).singleElement().satisfies(detail->{
            assertThat(detail.location()).isEqualTo("人物设定 > 王阿姨 > 档案描述");
            assertThat(detail.problem()).contains("第一次窥视对象");
            assertThat(detail.severity()).isEqualTo("必须修正");
        });
        assertThat(review.issues().getFirst()).contains("修改位置：人物设定", "建议：删除“初次”");
    }
    @Test void styleReviewUsesDedicatedPromptAndAction() throws Exception {
        var issue=Map.of("location","以“她真的想不起来”开头的句子","problem","对白之后重复解释",
                "evidence","她真的想不起来，不是回避，不是假装。","suggestion","删除这句","severity","建议优化");
        reply(Map.of("passed",true,"issues",List.of(issue),"mainlineResolved",false,
                "endingClear",false,"foreshadowingResolved",false),"stop");

        Review review=gateway().styleReview(styleReviewRequest(),generated());
        JsonNode body=mapper.readTree(received.get());
        JsonNode input=mapper.readTree(body.path("messages").path(1).path("content").asText());
        assertThat(body.path("messages").path(0).path("content").asText()).contains("中文小说文风编辑","passed 必须始终为 true");
        assertThat(input.path("action").asText()).isEqualTo("STYLE_REVIEW");
        assertThat(input.path("sourceAction").asText()).isEqualTo("STYLE_REVIEW");
        assertThat(review.issueDetails()).singleElement().extracting(ReviewIssue::severity).isEqualTo("建议优化");
    }
    @Test void continuityReviewUsesDedicatedPromptAndBoundedOutput() throws Exception {
        var issue=Map.of("location","第一章中以“他推门”开头的位置","problem","人物位置与上一章冲突",
                "evidence","当前写他在临江；已确认内容写他仍在海港","suggestion","补写移动过程或保持在海港","severity","必须修正");
        reply(Map.of("passed",false,"issues",List.of(issue),"mainlineResolved",false,
                "endingClear",false,"foreshadowingResolved",false),"stop");

        Review review=gateway().continuityReview(chapterRequest(),generated());
        JsonNode body=mapper.readTree(received.get());
        String prompt=body.path("messages").path(0).path("content").asText();
        assertThat(prompt).contains("连续性检查员","影子实验","不评价文风");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(1000);
        assertThat(review.passed()).isFalse();
        assertThat(review.issueDetails()).singleElement().extracting(ReviewIssue::severity).isEqualTo("必须修正");
    }
    @Test void plotForeshadowReviewUsesDedicatedPromptAndBoundedOutput() throws Exception {
        var issue=Map.of("location","第二幕的关键选择","problem","高潮结果与主线因果互相排斥",
                "evidence","候选写证据已经销毁；结局又依靠同一证据公开真相","suggestion","保留证据或改写结局的证明方式","severity","必须修正");
        reply(Map.of("passed",false,"issues",List.of(issue),"mainlineResolved",false,
                "endingClear",false,"foreshadowingResolved",false),"stop");

        Review review=gateway().plotForeshadowReview(request(),generated());
        JsonNode body=mapper.readTree(received.get());
        String prompt=body.path("messages").path(0).path("content").asText();
        assertThat(prompt).contains("情节与伏笔检查员","主线因果","影子实验");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(1000);
        assertThat(review.passed()).isFalse();
        assertThat(review.issueDetails()).singleElement().extracting(ReviewIssue::severity).isEqualTo("必须修正");
    }
    @Test void localStyleRewriteCanDeleteOneSentenceWithoutChangingSummaryOrFacts() throws Exception {
        reply(Map.of("operations",List.of(Map.of("op","REPLACE_TEXT","field","content",
                "oldText","她真的想不起来，不是回避，不是假装。","newText",""))),"stop");

        ModelGateway.Generated result=gateway().generate(localStyleRewriteRequest());

        assertThat(result.content()).isEqualTo("她摇了摇头。然后她推门离开。");
        assertThat(result.summary()).isEqualTo("她否认记得画面，随后离开");
        assertThat(result.facts()).containsExactly(new Fact("event_leave","EVENT","她随后离开房间","ACTIVE"));
        assertThat(mapper.readTree(received.get()).path("messages").path(0).path("content").asText())
                .contains("文风建议","最小 REPLACE_TEXT","不得使用 SET_CONTENT");
    }
    @Test void acceptsMissingReviewSeverityWithAConservativeDefault() throws Exception {
        var issue=Map.of("location","第四章正文","problem","日期不一致",
                "evidence","正文同时写有四月十二日和七月十二日","suggestion","统一日期");
        reply(Map.of("passed",false,"issues",List.of(issue),"mainlineResolved",false,
                "endingClear",false,"foreshadowingResolved",false),"stop");
        Review blocking=gateway().review(request(),generated());
        assertThat(blocking.passed()).isFalse();
        assertThat(blocking.issueDetails()).singleElement().extracting(ReviewIssue::severity).isEqualTo("必须修正");

        reply(Map.of("passed",true,"issues",List.of(issue),"mainlineResolved",false,
                "endingClear",false,"foreshadowingResolved",false),"stop");
        Review advisory=gateway().review(request(),generated());
        assertThat(advisory.passed()).isTrue();
        assertThat(advisory.issueDetails()).singleElement().extracting(ReviewIssue::severity).isEqualTo("建议优化");
    }
    @Test void structuredIssueSeverityOverridesAContradictoryModelPassedFlag() throws Exception {
        var optional=Map.of("location","当前内容","problem","可以润色",
                "evidence","属于表达偏好","suggestion","由作者决定","severity","作者决定");
        reply(Map.of("passed",false,"issues",List.of(optional),"mainlineResolved",false,
                "endingClear",false,"foreshadowingResolved",false),"stop");
        assertThat(gateway().review(request(),generated()).passed()).isTrue();

        var blocking=Map.of("location","当前内容","problem","明确事实冲突",
                "evidence","候选写甲，依据写乙","suggestion","修正冲突","severity","必须修正");
        reply(Map.of("passed",true,"issues",List.of(blocking),"mainlineResolved",false,
                "endingClear",false,"foreshadowingResolved",false),"stop");
        assertThat(gateway().review(request(),generated()).passed()).isFalse();
    }
    @Test void jsonModeIsExplicitAndAppliedToBothWriterAndReviewer() throws Exception {
        var jsonMode=new HttpModelGateway(mapper,baseUrl,"","fixture",5,1000,"max_tokens","json_object");
        reply(generated(),"stop");
        jsonMode.generate(request());
        assertThat(mapper.readTree(received.get()).path("response_format").path("type").asText()).isEqualTo("json_object");
        reply(new Review(true,List.of(),false,false,false),"stop");
        jsonMode.review(request(),generated());
        assertThat(mapper.readTree(received.get()).path("response_format").path("type").asText()).isEqualTo("json_object");
        var invalid=new HttpModelGateway(mapper,baseUrl,"","fixture",5,1000,"max_tokens","typo");
        assertThatThrownBy(()->invalid.generate(request())).hasMessageContaining("NOVELFORGE_RESPONSE_FORMAT");
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void planStructureRoundTripsAndRequiresExplicitBatchFields() throws Exception {
        Plan plan=new Plan(); plan.startChapter=1; plan.endChapter=2; plan.prepareNextAfterChapter=1;
        plan.finalBatch=false; plan.triggerReason="第一幕转折后筹备下一批"; plan.handoff="线索延续"; plan.assumptions="";
        plan.chapters=List.of(new ChapterBeat(1,"线索","发现线索"),new ChapterBeat(2,"追踪","追踪线索"));
        var value=mapper.valueToTree(new ModelGateway.Generated("首批规划","完整规划","摘要",List.of(),plan));
        reply(value,"stop");
        assertThat(gateway().generate(request()).plan().chapters).hasSize(2);
        ((com.fasterxml.jackson.databind.node.ObjectNode)value.path("plan")).remove("finalBatch");
        reply(value,"stop");
        assertThatThrownBy(()->gateway().generate(request())).hasMessageContaining("plan.finalBatch");
    }
    @Test void safelyRecoversOneExtraRootBraceAndExactChapterOrdinals() throws Exception {
        Plan plan=new Plan(); plan.startChapter=13; plan.endChapter=14; plan.prepareNextAfterChapter=13;
        plan.finalBatch=false; plan.triggerReason="承接上一批"; plan.handoff="继续追查"; plan.assumptions="";
        plan.chapters=List.of(new ChapterBeat(13,"雨夜来客","追查来客"),new ChapterBeat(14,"旧伞","找到旧伞"));
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(
                new ModelGateway.Generated("第三批规划","完整规划","摘要",List.of(),plan));
        var planNode=(com.fasterxml.jackson.databind.node.ObjectNode)value.path("plan");
        planNode.put("startChapter","第十三章"); planNode.put("endChapter","第十四章");
        planNode.put("prepareNextAfterChapter","第十三章");
        ((com.fasterxml.jackson.databind.node.ObjectNode)planNode.path("chapters").get(0)).put("number","第十三章");
        ((com.fasterxml.jackson.databind.node.ObjectNode)planNode.path("chapters").get(1)).put("number","第十四章");

        replyText(mapper.writeValueAsString(value)+"}","stop");
        ModelGateway.Generated recovered=gateway().generate(planRequest());

        assertThat(recovered.plan().startChapter).isEqualTo(13);
        assertThat(recovered.plan().endChapter).isEqualTo(14);
        assertThat(recovered.plan().chapters).extracting(ChapterBeat::number).containsExactly(13,14);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void refusesAmbiguousBracketAndNonCanonicalChapterNumberWithoutRetrying() throws Exception {
        String json=mapper.writeValueAsString(generated());
        replyText(json+"}}","stop");
        assertThatThrownBy(()->gateway().generate(request())).hasMessageContaining("无法唯一确定");

        Plan plan=new Plan(); plan.startChapter=13; plan.endChapter=13; plan.prepareNextAfterChapter=13;
        plan.finalBatch=false; plan.triggerReason="承接"; plan.handoff="继续"; plan.assumptions="";
        plan.chapters=List.of(new ChapterBeat(13,"来客","追查"));
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(
                new ModelGateway.Generated("规划","内容","摘要",List.of(),plan));
        ((com.fasterxml.jackson.databind.node.ObjectNode)value.path("plan")).put("startChapter","第十三章左右");
        reply(value,"stop");
        assertThatThrownBy(()->gateway().generate(planRequest())).hasMessageContaining("startChapter 必须是整数");
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void readsGzipReviewInsteadOfMisreportingJsonOrConnectionFailure() throws Exception {
        gzip=true; reply(new Review(true,List.of("逻辑核对完成"),false,false,false),"stop");
        assertThat(gateway().review(request(),generated()).passed()).isTrue();
    }
    String reviewSse(String finish,boolean done) throws Exception {
        String report=mapper.writeValueAsString(new Review(true,List.of(),false,false,false));
        String data="";
        for (String part : List.of(report.substring(0,15),report.substring(15)))
            data+="data: "+mapper.writeValueAsString(Map.of("choices",List.of(Map.of("index",0,"delta",Map.of("content",part)))))+"\n\n";
        data+="data: "+mapper.writeValueAsString(Map.of("choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason",finish))))+"\n\n";
        return ": heartbeat\n\n"+data+(done ? "data: [DONE]\n\n" : "");
    }
    @Test void readsCompletedSseFromCompatibleGatewayEvenWhenNonStreamingWasRequested() throws Exception {
        contentType.set("text/event-stream"); response.set(reviewSse("stop",true));
        assertThat(gateway().review(request(),generated()).passed()).isTrue();
        assertThat(mapper.readTree(received.get()).path("stream").asBoolean()).isFalse();
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void separatesBadOuterJsonHtmlAndUpstreamHttpErrorsWithoutLeakingResponse() throws Exception {
        response.set("<html>private upstream error and secret-token</html>"); contentType.set("text/html");
        assertThatThrownBy(()->gateway().review(request(),generated())).hasMessageContaining("UPSTREAM_HTML").hasMessageNotContaining("secret-token");
        response.set("{invalid}"); contentType.set("application/json");
        assertThatThrownBy(()->gateway().review(request(),generated())).hasMessageContaining("ENVELOPE_JSON");
        statusCode.set(401); response.set("{\"error\":{\"message\":\"secret-token\"}}");
        assertThatThrownBy(()->gateway().review(request(),generated())).hasMessageContaining("HTTP 401").hasMessageNotContaining("secret-token");
        assertThat(calls.get()).isEqualTo(3);
    }
    @Test void rejectsInterruptedTruncatedOrAmbiguousStreams() throws Exception {
        contentType.set("text/event-stream");
        response.set(reviewSse("stop",false));
        assertThatThrownBy(()->gateway().review(request(),generated())).hasMessageContaining("SSE_INCOMPLETE");
        response.set(reviewSse("length",true));
        assertThatThrownBy(()->gateway().review(request(),generated())).hasMessageContaining("OUTPUT_TRUNCATED");
        response.set(reviewSse("stop",true)+"data: {}\n\n");
        assertThatThrownBy(()->gateway().review(request(),generated())).hasMessageContaining("SSE_INVALID");
        response.set(reviewSse("stop",true).replace("\"index\":0","\"index\":1"));
        assertThatThrownBy(()->gateway().review(request(),generated())).hasMessageContaining("SSE_INVALID");
    }
    @Test void acceptsOuterBomButRejectsEmptyNullAndErrorEnvelopes() throws Exception {
        reply(new Review(false,List.of("存在未回收伏笔"),false,false,false),"stop"); response.set("\uFEFF"+response.get());
        assertThat(gateway().review(request(),generated()).passed()).isFalse();
        response.set("");
        assertThatThrownBy(()->gateway().review(request(),generated())).hasMessageContaining("EMPTY_RESPONSE");
        response.set("null");
        assertThatThrownBy(()->gateway().review(request(),generated())).hasMessageContaining("ENVELOPE_JSON");
        response.set("{\"error\":{\"message\":\"private novel text\"}}");
        assertThatThrownBy(()->gateway().review(request(),generated())).hasMessageContaining("UPSTREAM_ERROR").hasMessageNotContaining("private novel text");
    }
    @Test void deadlineIncludesStalledBodyAfterResponseHeaders() {
        CountDownLatch release=new CountDownLatch(1);
        server.createContext("/slow/chat/completions",exchange->{
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200,1000); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        var slow=new HttpModelGateway(mapper,baseUrl.replace("/v1","/slow"),"","fixture",1,1000,"max_tokens","none");
        try {
            Assertions.assertTimeoutPreemptively(Duration.ofSeconds(4),()->
                    assertThatThrownBy(()->slow.review(request(),generated())).hasMessageContaining("MODEL_TIMEOUT").hasMessageContaining("HTTP=200"));
            assertThat(calls.get()).isEqualTo(1);
        } finally { release.countDown(); }
    }
    @Test void boundsCompressedAndUncompressedBodySize() {
        response.set("x".repeat(4_000_001));
        assertThatThrownBy(()->gateway().review(request(),generated())).hasMessageContaining("RESPONSE_TOO_LARGE");
        gzip=true;
        assertThatThrownBy(()->gateway().review(request(),generated())).hasMessageContaining("RESPONSE_TOO_LARGE");
    }
    @Test void reportsConnectionFailureWithoutCallingItJsonParsing() {
        server.stop(0);
        assertThatThrownBy(()->gateway().review(request(),generated())).hasMessageContaining("MODEL_CONNECT").hasMessageNotContaining("JSON 解析失败");
    }
    @Test void clearlySeparatesReviewOperationFromOriginalWritingInstruction() throws Exception {
        var r=request();
        String context="{\"acceptedReferences\":[{\"kind\":\"OUTLINE\",\"content\":\"已确认主线\"}],\"revisionTarget\":{\"kind\":\"CHARACTERS\",\"content\":\"旧的人物动机\",\"facts\":[]}}";
        var rewrite=new ModelGateway.Request(Action.REWRITE,r.novel(),null,new ContextAssembler.Context(context,List.of(),1,1),"重写人物动机，不要解释");
        reply(new Review(true,List.of(),false,false,false),"stop");
        gateway().review(rewrite,generated());
        var input=mapper.readTree(mapper.readTree(received.get()).path("messages").get(1).path("content").asText());
        assertThat(input.path("action").asText()).isEqualTo("REVIEW");
        assertThat(input.path("sourceAction").asText()).isEqualTo("REWRITE");
        assertThat(input.has("instructions")).isFalse();
        assertThat(input.path("reviewRequirements").asText()).isEqualTo("重写人物动机，不要解释");
        assertThat(input.path("context").has("revisionTarget")).isFalse();
        assertThat(input.path("context").has("acceptedReferences")).isFalse();
        assertThat(input.path("context").path("authoritativeReferences").get(0).path("content").asText()).isEqualTo("已确认主线");
        assertThat(input.has("previousDraftForComparison")).isFalse();
        assertThat(input.has("comparisonRule")).isFalse();
    }
    @Test void manualEditReviewDoesNotReceiveAnyOldVersion() throws Exception {
        Novel novel=new Novel();novel.title="人工编辑复核";
        Artifact target=new Artifact();target.kind=Kind.OUTLINE;
        Version base=new Version();base.title="大纲";base.content="旧稿主线，预算三万字";base.summary="旧摘要";
        Version current=new Version();current.baseVersionId=base.id;current.title="大纲";
        current.content="旧稿主线，预算三点二万字";current.summary="新摘要";
        target.versions.addAll(List.of(base,current));novel.artifacts.add(target);
        String context="{\"acceptedReferences\":[],\"revisionTarget\":{\"kind\":\"OUTLINE\",\"title\":\"大纲\",\"content\":\"旧稿主线，预算三点二万字\",\"summary\":\"新摘要\",\"facts\":[],\"plan\":null}}";
        var request=new ModelGateway.Request(Action.REVIEW,novel,target,
                new ContextAssembler.Context(context,List.of(current.id),1,1),"只调整字数预算");
        reply(new Review(true,List.of(),false,false,false),"stop");
        gateway().review(request,new ModelGateway.Generated(current.title,current.content,current.summary,List.of(),null));
        JsonNode input=mapper.readTree(mapper.readTree(received.get()).path("messages").get(1).path("content").asText());
        assertThat(input.has("previousDraftForComparison")).isFalse();
        assertThat(input.path("candidate").path("content").asText()).isEqualTo(current.content);
        assertThat(input.has("comparisonRule")).isFalse();
    }
    @Test void appliesOfficialDeepSeekV4ChatCompatibilityWithoutReadingReasoningAsFinal() throws Exception {
        var deepseek=new HttpModelGateway(mapper,baseUrl,"","'DeepSeek-V4-Flash’",5,12000,"auto","none");
        replyMessage(Map.of("content",mapper.writeValueAsString(new Review(true,List.of(),false,false,false)),
                "reasoning_content","private chain of thought"),"stop");
        deepseek.review(request(),generated());
        JsonNode body=mapper.readTree(received.get());
        assertThat(body.path("model").asText()).isEqualTo("deepseek-v4-flash");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(12000);
        assertThat(body.has("max_completion_tokens")).isFalse();
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.has("reasoning_effort")).isFalse();

        reply(generated(),"stop");
        deepseek.generate(request());
        body=mapper.readTree(received.get());
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("high");

        reply(generated(),"stop");
        deepseek.outlineFoundation(request());
        body=mapper.readTree(received.get());
        assertThat(body.path("max_tokens").asInt()).isEqualTo(6000);
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.has("reasoning_effort")).isFalse();
        assertThat(body.path("messages").get(0).path("content").asText())
                .contains("只提供创作参谋材料，不编写完整大纲");

        reply(generated(),"stop");
        deepseek.generate(chapterRequest());
        body=mapper.readTree(received.get());
        assertThat(body.path("max_tokens").asInt()).isEqualTo(12000);
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.has("reasoning_effort")).isFalse();

        reply(generated(),"stop");
        deepseek.generate(planRequest());
        body=mapper.readTree(received.get());
        assertThat(body.path("max_tokens").asInt()).isEqualTo(12000);
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.has("reasoning_effort")).isFalse();

        reply(new ModelGateway.Generated("第三章","包含全部主事件的新正文","新摘要",
                List.of(new Fact("new_event","EVENT","新事件已发生","ACTIVE")),null),"stop");
        var rewrittenChapter=deepseek.generate(chapterRewriteRequest());
        body=mapper.readTree(received.get());
        assertThat(body.path("max_tokens").asInt()).isEqualTo(12000);
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(rewrittenChapter.content()).isEqualTo("包含全部主事件的新正文");
        assertThat(rewrittenChapter.facts()).extracting(Fact::key).containsExactly("new_event");
        assertThat(body.path("messages").get(0).path("content").asText()).contains("完整新章节","plan 必须为 null");

        reply(factPatch("王阿姨是宿舍管理员"),"stop");
        deepseek.generate(rewriteRequest());
        body=mapper.readTree(received.get());
        assertThat(body.path("max_tokens").asInt()).isEqualTo(4000);
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.has("reasoning_effort")).isFalse();
    }
    @Test void fullContentReplacementIsRejectedOutsideChapterArtifacts() throws Exception {
        reply(Map.of("operations",List.of(Map.of("op","SET_CONTENT","value","不应允许的人物设定整份替换"))),"stop");
        assertThatThrownBy(()->gateway().generate(rewriteRequest())).hasMessageContaining("只有章节正文允许整章重写");
    }
    @Test void strayPlanOperationCannotModifyAChapter() throws Exception {
        Map<String,Object> echoedPlan=Map.of(
                "startChapter",3,"endChapter",3,"prepareNextAfterChapter",3,"finalBatch",false,
                "triggerReason","模型误回显只读规划","handoff","不得写入章节","assumptions","",
                "chapters",List.of(Map.of("number",3,"title","第三章","purpose","只读依据")));
        reply(Map.of("operations",List.of(
                Map.of("op","REPLACE_TEXT","field","content","oldText","遗漏主事件的旧正文","newText","局部修改后的正文"),
                Map.of("op","SET_PLAN","plan",echoedPlan))),"stop");
        ModelGateway.Request full=chapterRewriteRequest();
        ModelGateway.Request local=new ModelGateway.Request(full.action(),full.novel(),full.target(),full.context(),"只做局部修改");
        ModelGateway.Generated generated=gateway().generate(local);
        assertThat(generated.content()).isEqualTo("局部修改后的正文");
        assertThat(generated.summary()).isEqualTo("旧摘要");
        assertThat(generated.plan()).isNull();
    }
    @Test void chapterEvidenceAuditOverridesAFalsePositiveGeneralReview() throws Exception {
        String summary="邱天勒索姚远并导致其自杀";
        Fact unsupported=new Fact("event_claim","EVENT","姚远因遭到邱天勒索而自杀","ACTIVE");
        ModelGateway.Generated candidate=new ModelGateway.Generated("第三章","邱天把周倩的事情告诉姚远，姚远随后跳楼自杀。",summary,List.of(unsupported),null);
        queueReply(new Review(true,List.of(),false,false,false));
        queueReply(new ChapterEvidenceAudit(
                List.of(new ChapterEvidenceAudit.Check("勒索姚远致其自杀",false,List.of())),
                List.of(new ChapterEvidenceAudit.Check(summary,false,List.of()),
                        new ChapterEvidenceAudit.Check(unsupported.detail(),false,List.of()))));
        Review checked=gateway().review(chapterRewriteRequest(),candidate);
        assertThat(checked.passed()).isFalse();
        assertThat(checked.issues()).anyMatch(issue->issue.contains("勒索姚远致其自杀"));
        assertThat(checked.issueDetails()).anySatisfy(issue->{
            assertThat(issue.location()).contains("档案增量中以");
            assertThat(issue.evidence()).contains(unsupported.detail());
        });
        assertThat(mapper.readTree(received.get()).path("messages").get(0).path("content").asText())
                .contains("满足其中一个选项即可","绝不能擅自改成全部选项","初体验","不要求提前命名");
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void nonVerbatimEvidenceBecomesBlockingIssueInsteadOfFailingTheTask() throws Exception {
        String content="邱天勒索姚远，姚远不堪受辱后自杀。";
        String summary="邱天勒索姚远并导致其自杀";
        ModelGateway.Generated candidate=new ModelGateway.Generated("第三章",content,summary,List.of(),null);
        queueReply(new Review(true,List.of(),false,false,false));
        queueReply(new ChapterEvidenceAudit(
                List.of(new ChapterEvidenceAudit.Check("勒索姚远致其自杀",true,List.of("邱天威胁姚远后使其自杀"))),
                List.of(new ChapterEvidenceAudit.Check(summary,true,List.of(content)))));
        Review checked=gateway().review(chapterRewriteRequest(),candidate);
        assertThat(checked.passed()).isFalse();
        assertThat(checked.issues()).anyMatch(issue->issue.contains("勒索姚远致其自杀"));
    }
    @Test void auditsCompoundArchiveByAtomicClaimAndNeverAsksProseToProveIt() throws Exception {
        String detail="王阿姨藏起现金，并把钥匙交给张洋";
        String content="王阿姨把现金塞进柜底。张洋始终没有出现。";
        ModelGateway.Generated candidate=new ModelGateway.Generated("第三章",content,"王阿姨藏起现金",
                List.of(new Fact("cash_handoff","EVENT",detail,"ACTIVE")),null);
        queueReply(new Review(true,List.of(),false,false,false));
        queueReply(new ChapterEvidenceAudit(List.of(new ChapterEvidenceAudit.Check("勒索姚远致其自杀",true,List.of(content))),
                List.of(new ChapterEvidenceAudit.Check("王阿姨藏起现金",true,List.of("王阿姨把现金塞进柜底")),
                        new ChapterEvidenceAudit.Check("把钥匙交给张洋",false,List.of()))));

        Review checked=gateway().review(chapterRewriteRequest(),candidate);

        assertThat(checked.issueDetails()).singleElement().satisfies(issue->{
            assertThat(issue.problem()).contains("把钥匙交给张洋");
            assertThat(issue.evidence()).contains("当前档案原文：“"+detail+"”");
            assertThat(issue.suggestion()).contains("只修改档案条目","不要为通过检查向正文补写解释句");
        });
        JsonNode auditInput=mapper.readTree(mapper.readTree(received.get()).path("messages").get(1).path("content").asText());
        assertThat(auditInput.path("claims").findValuesAsText("text"))
                .containsExactly("王阿姨藏起现金","把钥匙交给张洋");
    }
    @Test void emptyDeepSeekFinalReportsReasoningAndUsageWithoutLeakingIt() throws Exception {
        var deepseek=new HttpModelGateway(mapper,baseUrl,"","deepseek-v4-flash",5,12000,"auto","json_object");
        String privateReasoning="private reasoning must never be shown";
        response.set(mapper.writeValueAsString(Map.of(
                "choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","","reasoning_content",privateReasoning))),
                "usage",Map.of("completion_tokens",987,"completion_tokens_details",Map.of("reasoning_tokens",987)))));
        assertThatThrownBy(()->deepseek.review(request(),generated()))
                .hasMessageContaining("EMPTY_FINAL_CONTENT").hasMessageContaining("DeepSeek 官方说明")
                .hasMessageContaining("含推理=true").hasMessageContaining("输出token=987").hasMessageContaining("推理token=987")
                .hasMessageNotContaining(privateReasoning);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void emptyDeepSeekPlanReportsTheActualNonThinkingRequestMode() throws Exception {
        var deepseek=new HttpModelGateway(mapper,baseUrl,"","deepseek-v4-flash",5,12000,"auto","none");
        response.set(mapper.writeValueAsString(Map.of(
                "choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","","reasoning_content","private"))),
                "usage",Map.of("completion_tokens",2375,"completion_tokens_details",Map.of("reasoning_tokens",2375)))));
        assertThatThrownBy(()->deepseek.generate(planRequest()))
                .hasMessageContaining("EMPTY_FINAL_CONTENT")
                .hasMessageContaining("已发送非思考模式参数")
                .hasMessageNotContaining("检查请求");
        JsonNode body=mapper.readTree(received.get());
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.has("reasoning_effort")).isFalse();
        assertThat(calls.get()).isEqualTo(1);
    }
}
