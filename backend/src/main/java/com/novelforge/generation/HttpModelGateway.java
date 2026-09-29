package com.novelforge.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Review;
import com.novelforge.shared.Problem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;

@Component
@ConditionalOnProperty(name="novelforge.model.mode", havingValue="http")
public class HttpModelGateway implements ModelGateway {
    private static final Logger log=LoggerFactory.getLogger(HttpModelGateway.class);
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String baseUrl, apiKey, model, tokenField, responseFormat;
    private final boolean deepSeek;
    private final ModelJsonReader outputReader;
    private final ModelResponseDecoder responseDecoder;
    private final FactAtomicityPolicy factAtomicity=new FactAtomicityPolicy();
    private final int timeout, maxTokens;
    public HttpModelGateway(ObjectMapper mapper,
        @Value("${novelforge.model.base-url}") String baseUrl,
        @Value("${novelforge.model.api-key}") String apiKey,
        @Value("${novelforge.model.name}") String model,
        @Value("${novelforge.model.timeout-seconds}") int timeout,
        @Value("${novelforge.model.max-output-tokens}") int maxTokens,
        @Value("${NOVELFORGE_TOKEN_LIMIT_FIELD:auto}") String tokenField,
        @Value("${novelforge.model.response-format:none}") String responseFormat) {
        this.mapper=mapper; this.baseUrl=baseUrl; this.apiKey=apiKey; this.model=canonicalModel(model);
        this.deepSeek=Set.of("deepseek-v4-flash","deepseek-v4-pro","deepseek-v4-flash-vision-exp").contains(this.model);
        this.timeout=timeout; this.maxTokens=maxTokens;
        this.tokenField="auto".equals(tokenField) ? (deepSeek ? "max_tokens" : "max_completion_tokens") : tokenField;
        this.responseFormat=responseFormat; this.outputReader=new ModelJsonReader(mapper);
        this.responseDecoder=new ModelResponseDecoder(mapper);
    }
    public String mode() { return "http"; }
    public boolean ready() { return !baseUrl.isBlank() && !model.isBlank(); }
    public Generated generate(Request request) {
        if (request.action()==Action.REWRITE) return withStage("写作生成",()->{
            if (request.target()==null || request.target().latest()==null) throw new Problem(409,"修订目标不存在或没有版本");
            if (request.target().kind==com.novelforge.novel.Novel.Kind.OUTLINE)
                return call("prompts/writer.txt",input(request,null,false),Generated.class,
                        false,maxTokens,"outline-rewrite");
            if (request.target().kind==com.novelforge.novel.Novel.Kind.CHAPTER && fullChapterRewrite(request.instructions())) {
                Generated rewritten=call("prompts/chapter-rewriter.txt",input(request,null,false),Generated.class,
                        false,maxTokens,"chapter-rewrite");
                return new Generated(rewritten.title(),rewritten.content(),rewritten.summary(),rewritten.facts(),null);
            }
            int rewriteLimit=request.target().kind==com.novelforge.novel.Novel.Kind.CHAPTER ? maxTokens : Math.min(maxTokens,4000);
            RewritePatch patch=call("prompts/rewriter.txt",input(request,null,false),RewritePatch.class,
                    false,rewriteLimit,"rewrite");
            if (request.instructions()!=null && request.instructions().startsWith(StylePatchGuard.INSTRUCTION_MARKER))
                return StylePatchGuard.apply(request.target().latest(),patch,request.target().kind);
            return RewritePatchApplier.apply(request.target().latest(),patch,request.target().kind);
        });
        // DeepSeek reasoning tokens share the completion budget. Plans and long chapter prose must
        // reserve that budget for the structured final JSON instead of risking reasoning-only output.
        boolean thinking=request.action()==Action.OUTLINE || request.action()==Action.CHARACTERS;
        return withStage("写作生成", () -> call("prompts/writer.txt", input(request, null, false), Generated.class,
                thinking,maxTokens,"generate"));
    }

    public Generated outlineFoundation(Request request) {
        return withStage("大纲人物世界参谋",()->call("prompts/outline-foundation.txt",
                input(request,null,false),Generated.class,false,Math.min(maxTokens,6000),"outline-foundation"));
    }

    public Review outlineContinuityReview(Request request,Generated candidate) {
        return withStage("大纲连贯性检查",()->call("prompts/outline-continuity-reviewer.txt",
                input(request,candidate,true),Review.class,false,Math.min(maxTokens,6000),"outline-continuity-review"));
    }

    public Review outlinePlotReview(Request request,Generated candidate) {
        return withStage("大纲情节伏笔检查",()->call("prompts/outline-plot-reviewer.txt",
                input(request,candidate,true),Review.class,false,Math.min(maxTokens,6000),"outline-plot-review"));
    }

    private boolean fullChapterRewrite(String instructions) {
        if (instructions==null) return false;
        String text=instructions.replaceAll("\\s+","");
        return text.contains("整章重写") || text.contains("重写整章") || text.contains("整体重写本章");
    }
    public Review review(Request request, Generated candidate) {
        return withStage(request.action()==Action.COMPLETE ? "完结检查" : "一致性检查", () -> {
            Review review=call("prompts/reviewer.txt", input(request, candidate, true), Review.class,false,maxTokens,"review");
            if (candidate!=null && request.target()!=null && request.target().kind==com.novelforge.novel.Novel.Kind.CHAPTER) {
                ChapterEvidenceAudit audit=call("prompts/chapter-auditor.txt",chapterAuditInput(request,candidate),
                        ChapterEvidenceAudit.class,false,maxTokens,"chapter-audit");
                review=applyChapterAudit(request,candidate,review,audit);
            }
            return review;
        });
    }

    public Review styleReview(Request request, Generated candidate) {
        return withStage("文风检查", () -> call("prompts/style-reviewer.txt", input(request, candidate, true),
                Review.class, false, maxTokens, "style-review"));
    }

    public Review continuityReview(Request request, Generated candidate) {
        return withStage("连续性影子检查", () -> call("prompts/continuity-reviewer.txt", input(request, candidate, true),
                Review.class, false, Math.min(maxTokens,6000), "continuity-shadow-review"));
    }

    public Review plotForeshadowReview(Request request, Generated candidate) {
        return withStage("情节与伏笔影子检查", () -> call("prompts/plot-foreshadow-reviewer.txt", input(request, candidate, true),
                Review.class, false, Math.min(maxTokens,6000), "plot-foreshadow-shadow-review"));
    }

    private String chapterAuditInput(Request request, Generated candidate) {
        try {
            JsonNode context=mapper.readTree(request.context().json());
            var input=new LinkedHashMap<String,Object>();
            input.put("chapterContent",candidate.content());
            JsonNode items=context.path("currentChapterPlan").path("acceptanceItems");
            input.put("planAcceptanceItems",items.isArray()?items:List.of());
            var claims=new ArrayList<Map<String,String>>();
            if (candidate.facts()!=null) for (var fact:candidate.facts())
                for (String claim:factAtomicity.claims(fact.detail()))
                    claims.add(Map.of("location","档案增量中以“"+shortText(fact.detail())+"”开头的条目","text",claim));
            input.put("claims",claims);
            return mapper.writeValueAsString(input);
        } catch (Exception e) { throw new Problem(500,"[REQUEST_ENCODING] 本地章节证据检查请求无法编码；候选内容未写入"); }
    }

    private Review applyChapterAudit(Request request, Generated candidate, Review review, ChapterEvidenceAudit audit) {
        try {
            JsonNode context=mapper.readTree(request.context().json());
            List<String> expectedPlan=new ArrayList<>();
            context.path("currentChapterPlan").path("acceptanceItems").forEach(item->expectedPlan.add(item.asText()));
            List<AuditClaim> expectedClaims=new ArrayList<>();
            if (candidate.facts()!=null) candidate.facts().forEach(fact->
                    factAtomicity.claims(fact.detail()).forEach(claim->expectedClaims.add(new AuditClaim(fact.detail(),claim))));
            List<ChapterEvidenceAudit.Check> planChecks=verifiedChecks(expectedPlan,audit.planChecks(),candidate.content());
            List<ChapterEvidenceAudit.Check> claimChecks=verifiedChecks(expectedClaims.stream().map(AuditClaim::claim).toList(),
                    audit.claimChecks(),candidate.content());
            Review checked=review;
            for (ChapterEvidenceAudit.Check check:planChecks) if (!check.satisfied())
                checked=checked.withIssue(new com.novelforge.novel.Novel.ReviewIssue("当前章节正文 > 章节规划",
                        "正文没有完整落实规划要求："+check.requirement(),"证据式检查未能在正文中找到同时支持人物、动作、对象和结果的原文",
                        "补写该情节并明确其行动与结果，不要只在摘要或档案中声明","必须修正"));
            for (int i=0;i<claimChecks.size();i++) if (!claimChecks.get(i).satisfied()) {
                AuditClaim expected=expectedClaims.get(i);
                String claim=expected.claim();
                String location="当前章节 > 档案增量中以“"+shortText(expected.factDetail())+"”开头的条目";
                checked=checked.withIssue(new com.novelforge.novel.Novel.ReviewIssue(location,
                        "档案中的这个原子声明缺少正文依据：“"+claim+"”",
                        "当前档案原文：“"+expected.factDetail()+"”｜正文中没有找到支持该原子声明的逐字证据",
                        "只修改档案条目：删除无依据的声明，或拆分并保留正文已经支持的原子事实；不要为通过检查向正文补写解释句",
                        "必须修正"));
            }
            return checked;
        } catch (Problem p) { throw p; }
        catch (Exception e) { throw new Problem(502,"章节证据检查结果无法核对；候选内容未写入"); }
    }

    private List<ChapterEvidenceAudit.Check> verifiedChecks(List<String> expected,List<ChapterEvidenceAudit.Check> actual,String content) {
        List<ChapterEvidenceAudit.Check> source=actual==null?List.of():actual;
        var verified=new ArrayList<ChapterEvidenceAudit.Check>();
        for (String requirement:expected) {
            var check=source.stream().filter(item->requirement.equals(item.requirement())).findFirst().orElse(null);
            boolean supported=check!=null && check.satisfied() && check.evidence()!=null && !check.evidence().isEmpty()
                    && check.evidence().stream().anyMatch(quote->quoteSupported(content,quote));
            verified.add(new ChapterEvidenceAudit.Check(requirement,supported,supported?List.copyOf(check.evidence()):List.of()));
        }
        return verified;
    }

    private boolean quoteSupported(String content,String quote) {
        if (quote==null || quote.isBlank()) return false;
        if (content.contains(quote)) return true;
        String normalizedContent=content.replaceAll("[^\\p{IsHan}A-Za-z0-9]","");
        String normalizedQuote=quote.replaceAll("[^\\p{IsHan}A-Za-z0-9]","");
        return normalizedQuote.length()>=4 && normalizedContent.contains(normalizedQuote);
    }

    private String shortText(String value) {
        String text=value==null?"未命名内容":value.replaceAll("\\s+"," ").strip();
        return text.length()<=24?text:text.substring(0,24)+"…";
    }
    private record AuditClaim(String factDetail,String claim) {}
    private <T> T withStage(String stage, java.util.function.Supplier<T> operation) {
        try { return operation.get(); }
        catch (Problem p) { throw new Problem(p.status, stage + "失败：" + p.getMessage()); }
    }
    private String input(Request r, Generated candidate, boolean reviewing) {
        try {
            var data = new LinkedHashMap<String,Object>();
            ObjectNode context=(ObjectNode)mapper.readTree(r.context().json());
            if (reviewing) {
                context.remove("revisionTarget");
                JsonNode accepted=context.remove("acceptedReferences");
                if (accepted != null) context.set("authoritativeReferences",accepted);
                data.put("action", r.action()==Action.COMPLETE ? Action.COMPLETE
                        : r.action()==Action.STYLE_REVIEW ? Action.STYLE_REVIEW : Action.REVIEW);
                data.put("sourceAction", r.action());
                data.put("reviewRequirements", r.instructions());
                data.put("candidateKind", r.target()==null ? (r.action()==Action.COMPLETE ? "NOVEL" : r.action().name()) : r.target().kind);
                data.put("candidate", candidate);
            } else {
                data.put("action", r.action()); data.put("instructions", r.instructions());
            }
            data.put("context", context);
            return mapper.writeValueAsString(data);
        } catch (Exception e) { throw new Problem(500, "[REQUEST_ENCODING] 本地模型请求无法编码；原有内容未覆盖"); }
    }
    private <T> T call(String prompt, String input, Class<T> type, boolean thinking, int outputLimit, String operation) {
        if (!ready()) throw new Problem(503, "请配置模型地址 NOVELFORGE_MODEL_BASE_URL 和模型名 NOVELFORGE_MODEL_NAME");
        var trace=new Trace(operation);
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        AtomicReference<HttpResponse.ResponseInfo> headers=new AtomicReference<>();
        try {
            if (timeout<1 || maxTokens<1) throw new Problem(503,"[MODEL_CONFIG] 模型超时和输出额度必须大于零");
            URI uri = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
            if (uri.getHost()==null) throw new Problem(503,"[MODEL_CONFIG] 模型基础地址无效，必须包含协议和主机名");
            boolean loopback = Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost());
            if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && loopback))
                throw new Problem(503, "远程模型地址必须使用 HTTPS；仅本机模型允许 HTTP");
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new Problem(503, "模型基础地址不能包含凭据、查询参数或片段");
            trace.phase="REQUEST_ENCODING";
            String system = new ClassPathResource(prompt).getContentAsString(StandardCharsets.UTF_8);
            var body = new LinkedHashMap<String,Object>();
            body.put("model", model); body.put("stream", false);
            body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", input)));
            if (deepSeek) {
                body.put("thinking",Map.of("type",thinking ? "enabled" : "disabled"));
                if (thinking) body.put("reasoning_effort","high");
            }
            if (!List.of("max_tokens", "max_completion_tokens").contains(tokenField)) throw new Problem(503, "不支持的模型 token 上限字段");
            body.put(tokenField, outputLimit);
            if (!Set.of("none", "json_object").contains(responseFormat))
                throw new Problem(503, "NOVELFORGE_RESPONSE_FORMAT 仅支持 none 或 json_object");
            // Opt in: not every Chat Completions-compatible service supports JSON mode.
            if (responseFormat.equals("json_object")) body.put("response_format", Map.of("type", "json_object"));
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .header("Accept-Encoding","gzip").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
            trace.phase="HTTP_REQUEST";
            pending=client.sendAsync(builder.build(), info->{ headers.set(info); return new BoundedModelBody(); });
            var response=pending.get(timeout,TimeUnit.SECONDS);
            trace.headers(headers.get()); trace.bytes=response.body().length;
            if (response.statusCode() != 200) throw httpFailure(response.statusCode());
            trace.phase="HTTP_RESPONSE";
            var decoded=responseDecoder.decode(response.body(),response.headers().firstValue("Content-Type").orElse(""),
                    response.headers().firstValue("Content-Encoding").orElse(""));
            JsonNode choice=decoded.choice();
            trace.reasoning=decoded.reasoningPresent(); trace.outputTokens=decoded.outputTokens(); trace.reasoningTokens=decoded.reasoningTokens();
            if (!choice.isObject() || !choice.path("message").isObject())
                throw new Problem(502, "模型响应缺少 choices[0].message，请检查 Chat Completions 兼容接口；原有内容未覆盖");
            if ("content_filter".equals(choice.path("finish_reason").asText()))
                throw new Problem(502, "模型服务拦截了本次内容请求；原有内容未覆盖");
            JsonNode refusal=choice.path("message").path("refusal");
            if (!refusal.isMissingNode() && !refusal.isNull() && !refusal.asText("").isBlank())
                throw new Problem(502, "模型拒绝了本次内容请求；原有内容未覆盖");
            String finish=choice.path("finish_reason").asText();
            if ("tool_calls".equals(finish) || "function_call".equals(finish))
                throw new Problem(502,"[UNEXPECTED_TOOL_CALL] 模型返回工具调用而非文本，不能作为检查报告或正文保存");
            if ("length".equals(finish)) throw new Problem(502, "[OUTPUT_TRUNCATED] 模型输出被截断，请调整输出额度后重试");
            if (!"stop".equals(finish)) throw new Problem(502,"[FINISH_REASON] 模型未返回正常结束标志 stop，请检查接口兼容性");
            trace.phase="MODEL_JSON";
            String finalContent=responseText(choice.path("message").path("content"));
            if (finalContent.isBlank()) throw emptyFinal(decoded,thinking);
            return outputReader.read(finalContent, type);
        } catch (Problem p) { throw diagnosed(p,trace,null); }
        catch (InterruptedException e) {
            if (pending!=null) pending.cancel(true);
            Thread.currentThread().interrupt(); throw diagnosed(new Problem(502,"[CANCELLED] 模型请求已取消或服务中断"),trace,e);
        } catch (TimeoutException e) {
            if (pending!=null) pending.cancel(true);
            trace.headers(headers.get());
            throw diagnosed(new Problem(502,"[MODEL_TIMEOUT] 模型请求超时（包括读取完整响应）；请检查服务速度和 NOVELFORGE_MODEL_TIMEOUT"),trace,e);
        } catch (ExecutionException e) {
            trace.headers(headers.get());
            throw diagnosed(transportFailure(e),trace,e.getCause());
        } catch (IllegalArgumentException e) {
            throw diagnosed(new Problem(503,"[MODEL_CONFIG] 本地模型配置或请求参数无效，请检查地址、请求头与额度配置"),trace,e);
        } catch (IOException e) {
            throw diagnosed(new Problem(500,"[REQUEST_ENCODING] 本地提示文件读取或请求 JSON 编码失败"),trace,e);
        } catch (Exception e) {
            throw diagnosed(new Problem(500,"[MODEL_INTERNAL] 本地模型适配发生内部异常，请按诊断编号查看日志"),trace,e);
        }
    }
    private Problem transportFailure(Throwable error) {
        for (Throwable cause=error; cause!=null; cause=cause.getCause()) {
            if (cause instanceof BoundedModelBody.BodyTooLarge) return new Problem(502,"[RESPONSE_TOO_LARGE] 模型响应超过 4MB 限制");
            if (cause instanceof HttpTimeoutException) return new Problem(502,"[MODEL_TIMEOUT] 模型连接或响应超时");
            if (cause instanceof SSLException) return new Problem(502,"[MODEL_TLS] 模型 HTTPS 握手或证书验证失败，请检查证书链；不要关闭证书校验");
            if (cause instanceof ConnectException) return new Problem(502,"[MODEL_CONNECT] 无法连接模型服务，请检查服务地址、网络和代理配置");
        }
        return new Problem(502,"[MODEL_IO] 模型网络传输失败或响应正文读取中断，请检查网络与中转服务");
    }
    private Problem httpFailure(int status) {
        String hint=switch(status) {
            case 401,403 -> "请检查模型密钥、访问权限或服务端拦截规则";
            case 404 -> "请检查基础地址，系统会追加 /chat/completions";
            case 400,422 -> "请检查服务支持的模型名、token 字段、JSON 模式和上下文额度";
            case 429 -> "请检查模型额度、余额或请求频率";
            default -> "请检查模型服务或中转网关状态";
        };
        return new Problem(502,"[UPSTREAM_HTTP] 模型接口返回 HTTP "+status+"；"+hint);
    }
    private Problem emptyFinal(ModelResponseDecoder.Decoded decoded, boolean thinking) {
        String reason=decoded.reasoningPresent()
                ? "模型返回了 reasoning_content，但没有最终 content；系统不会把思维链当作检查报告或正文"
                : "模型正常结束但最终 content 为空";
        if (deepSeek && responseFormat.equals("json_object"))
            reason+="。DeepSeek 官方说明 JSON Output 可能偶发空内容；可将 NOVELFORGE_RESPONSE_FORMAT 设为 none 后重启";
        else if (deepSeek && thinking)
            reason+="。本次 DeepSeek 请求启用了思考模式，但推理结束后没有给出最终答案；请改用非思考模式后手动重试";
        else if (deepSeek)
            reason+="。本次 DeepSeek 请求已发送非思考模式参数；请核对服务是否正确支持 thinking.type=disabled";
        return new Problem(502,"[EMPTY_FINAL_CONTENT] "+reason);
    }
    private Problem diagnosed(Problem problem, Trace trace, Throwable error) {
        String exception=error==null ? "none" : error.getClass().getSimpleName();
        log.warn("Model call failed: trace={} operation={} phase={} http={} media={} bytes={} reasoning={} outputTokens={} reasoningTokens={} exception={}",
                trace.id,trace.operation,trace.phase,trace.status,trace.media,trace.bytes,trace.reasoning,trace.outputTokens,trace.reasoningTokens,exception);
        String message=problem.getMessage();
        if (!message.contains("原有内容未覆盖")) message+="；原有内容未覆盖，未自动重试";
        return new Problem(problem.status,message+" [诊断="+trace.id+",阶段="+trace.phase+",HTTP="+trace.status+",类型="+trace.media+",字节="+trace.bytes+",含推理="+trace.reasoning+",输出token="+trace.outputTokens+",推理token="+trace.reasoningTokens+"]");
    }
    private static class Trace {
        final String id=UUID.randomUUID().toString().substring(0,8),operation;
        String phase="CONFIG",media="unknown"; int status=-1,bytes=-1; boolean reasoning;
        long outputTokens=-1,reasoningTokens=-1;
        Trace(String operation) { this.operation=operation; }
        void headers(HttpResponse.ResponseInfo info) {
            if (info==null) return;
            status=info.statusCode();
            String type=info.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            media=type.contains("application/json") ? "JSON" : type.contains("text/event-stream") ? "SSE" : type.contains("text/html") ? "HTML" : "other";
        }
    }
    private static String canonicalModel(String raw) {
        String value=raw==null ? "" : raw.strip();
        if (value.length()>1 && "'\"‘’".indexOf(value.charAt(0))>=0 && "'\"‘’".indexOf(value.charAt(value.length()-1))>=0)
            value=value.substring(1,value.length()-1).strip();
        for (String id : List.of("deepseek-v4-flash","deepseek-v4-pro","deepseek-v4-flash-vision-exp"))
            if (id.equalsIgnoreCase(value)) return id;
        return value;
    }
    private String responseText(JsonNode content) {
        if (content.isTextual()) return content.textValue();
        if (content.isArray()) {
            StringBuilder text=new StringBuilder();
            for (JsonNode part : content) {
                if ("refusal".equals(part.path("type").asText()))
                    throw new Problem(502, "模型拒绝了本次内容请求；原有内容未覆盖");
                if (!"text".equals(part.path("type").asText()) || !part.path("text").isTextual())
                    throw new Problem(502, "模型 content 包含不支持的内容块；原有内容未覆盖");
                text.append(part.path("text").textValue());
            }
            return text.toString();
        }
        if (content.isNull() || content.isMissingNode()) return "";
        throw new Problem(502, "模型 content 不是文本或文本块数组；原有内容未覆盖");
    }
}
