package com.novelforge.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.shared.Problem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Decode the HTTP envelope separately from the model's own generated JSON. */
final class ModelResponseDecoder {
    record Decoded(JsonNode choice, boolean reasoningPresent, long outputTokens, long reasoningTokens) {}
    private final ObjectMapper mapper;
    ModelResponseDecoder(ObjectMapper mapper) { this.mapper=mapper; }

    Decoded decode(byte[] bytes, String contentType, String encoding) {
        if ("gzip".equalsIgnoreCase(encoding)) {
            try (var gzip=new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                bytes=gzip.readNBytes(BoundedModelBody.LIMIT+1);
                if (bytes.length>BoundedModelBody.LIMIT) throw failure("RESPONSE_TOO_LARGE","模型响应解压后超过 4MB 限制");
            } catch (IOException e) { throw failure("RESPONSE_ENCODING","模型 gzip 响应损坏或未完整传输"); }
        } else if (!encoding.isBlank() && !"identity".equalsIgnoreCase(encoding)) {
            throw failure("RESPONSE_ENCODING","模型返回不支持的 HTTP 压缩编码，请检查中转配置");
        }
        String text;
        try { text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().strip().replaceFirst("^\uFEFF", "").strip(); }
        catch (CharacterCodingException e) { throw failure("RESPONSE_ENCODING","模型接口响应不是有效 UTF-8 文本"); }
        if (text.isEmpty()) throw failure("EMPTY_RESPONSE","模型接口返回空响应体");
        if (contentType.toLowerCase(Locale.ROOT).contains("text/html") || text.startsWith("<"))
            throw failure("UPSTREAM_HTML","模型接口返回 HTML 页面而非 API 数据，请检查模型地址、中转服务或网关");
        if (contentType.toLowerCase(Locale.ROOT).contains("text/event-stream") || text.startsWith("data:") || text.startsWith(":"))
            return streamChoice(text);
        JsonNode envelope=json(text);
        JsonNode choice=onlyChoice(envelope);
        JsonNode reasoning=choice.path("message").path("reasoning_content");
        return new Decoded(choice,reasoning.isTextual() && !reasoning.textValue().isBlank(),
                outputTokens(envelope),reasoningTokens(envelope));
    }
    private JsonNode json(String text) {
        try {
            JsonNode node=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(text);
            if (node==null || !node.isObject()) throw failure("ENVELOPE_JSON","模型接口外层必须是 JSON 对象");
            if (node.hasNonNull("error")) throw failure("UPSTREAM_ERROR","模型服务在响应体中返回错误，请检查服务端请求日志或额度");
            return node;
        } catch (JsonProcessingException e) {
            var location=e.getLocation();
            String position=location==null ? "" : "（行 " + location.getLineNr()+"，列 "+location.getColumnNr()+"）";
            throw failure("ENVELOPE_JSON","模型接口外层 JSON 解析失败"+position+"，尚未进入模型内容解析");
        }
    }
    private JsonNode onlyChoice(JsonNode node) {
        JsonNode choices=node.path("choices");
        if (!choices.isArray() || choices.size()!=1 || !choices.get(0).isObject())
            throw failure("ENVELOPE_SHAPE","模型响应须有唯一 choices[0].message，请检查 Chat Completions 兼容接口");
        return choices.get(0);
    }
    private Decoded streamChoice(String text) {
        var state=new StreamState();
        StringBuilder event=new StringBuilder();
        for (String line : text.split("\\r\\n|\\n|\\r",-1)) {
            if (line.isEmpty()) {
                if (!event.isEmpty()) { consumeEvent(event.toString(),state); event.setLength(0); }
            } else if (line.startsWith("data:")) {
                if (!event.isEmpty()) event.append('\n');
                String value=line.substring(5); event.append(value.startsWith(" ") ? value.substring(1) : value);
            } else if (!(line.startsWith(":") || line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:"))) {
                throw failure("SSE_INVALID","模型流式响应包含无法识别的事件行");
            }
        }
        if (!event.isEmpty()) consumeEvent(event.toString(),state);
        if (!state.done || state.finish==null)
            throw failure("SSE_INCOMPLETE","模型流式响应未完整结束，缺少 finish_reason 或 [DONE]；不保存部分内容");
        JsonNode choice=mapper.valueToTree(Map.of("finish_reason",state.finish,"message",Map.of("content",state.text.toString())));
        return new Decoded(choice,state.reasoningPresent,state.outputTokens,state.reasoningTokens);
    }
    private void consumeEvent(String event, StreamState state) {
        if (state.done) throw failure("SSE_INVALID","模型流式响应结束后仍返回数据");
        if (event.equals("[DONE]")) { state.done=true; return; }
        JsonNode node=json(event);
        if (node.has("usage")) state.usage(node);
        if (node.path("choices").isArray() && node.path("choices").isEmpty() && node.hasNonNull("usage")) return;
        JsonNode choice=onlyChoice(node);
        if (!choice.path("index").isIntegralNumber() || choice.path("index").asInt()!=0)
            throw failure("SSE_INVALID","模型流式响应的候选编号不合法");
        if (state.finish!=null) throw failure("SSE_INVALID","模型候选已结束却继续返回内容");
        JsonNode delta=choice.path("delta");
        if (!delta.isObject()) throw failure("SSE_INVALID","模型流式响应缺少 delta 对象");
        if (delta.hasNonNull("refusal") && !delta.path("refusal").asText().isBlank())
            throw failure("MODEL_REFUSAL","模型拒绝了本次内容请求");
        if (delta.hasNonNull("tool_calls") || delta.hasNonNull("function_call"))
            throw failure("UNEXPECTED_TOOL_CALL","模型返回了工具调用而非文本，本项目没有为写作或检查启用工具调用");
        if (delta.hasNonNull("content")) {
            if (!delta.path("content").isTextual()) throw failure("SSE_INVALID","模型流式 delta.content 不是文本");
            state.text.append(delta.path("content").textValue());
        }
        if (delta.hasNonNull("reasoning_content")) {
            if (!delta.path("reasoning_content").isTextual()) throw failure("SSE_INVALID","模型流式 reasoning_content 不是文本");
            state.reasoningPresent |= !delta.path("reasoning_content").textValue().isBlank();
        }
        if (choice.hasNonNull("finish_reason")) {
            if (!choice.path("finish_reason").isTextual()) throw failure("SSE_INVALID","模型 finish_reason 类型错误");
            state.finish=choice.path("finish_reason").textValue();
        }
    }
    private long outputTokens(JsonNode envelope) {
        JsonNode usage=envelope.path("usage");
        return usage.has("completion_tokens") ? usage.path("completion_tokens").asLong(-1) : usage.path("output_tokens").asLong(-1);
    }
    private long reasoningTokens(JsonNode envelope) {
        JsonNode usage=envelope.path("usage");
        JsonNode details=usage.has("completion_tokens_details") ? usage.path("completion_tokens_details") : usage.path("output_tokens_details");
        return details.path("reasoning_tokens").asLong(-1);
    }
    private class StreamState {
        final StringBuilder text=new StringBuilder(); String finish; boolean done,reasoningPresent;
        long outputTokens=-1,reasoningTokens=-1;
        void usage(JsonNode envelope) { outputTokens=outputTokens(envelope); reasoningTokens=reasoningTokens(envelope); }
    }
    private Problem failure(String code,String message) { return new Problem(502,"["+code+"] "+message); }
}
