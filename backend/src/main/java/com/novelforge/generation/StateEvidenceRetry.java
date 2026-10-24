package com.novelforge.generation;

import com.novelforge.shared.Problem;
import java.util.function.Function;

/** One bounded retry for repairable model state mistakes; the strict validator remains authoritative. */
public final class StateEvidenceRetry {
    private StateEvidenceRetry() {}
    public record Result(StateExtractionPolicy.ValidatedState state,String warning) {}

    public static StateExtractionPolicy.ValidatedState extractAndValidate(
            Function<ModelGateway.Request,ModelGateway.StateExtraction> extractor,
            ModelGateway.Request request,ModelGateway.Generated candidate,StateExtractionPolicy policy) {
        return extractWithDiagnostics(extractor,request,candidate,policy).state();
    }

    public static Result extractWithDiagnostics(
            Function<ModelGateway.Request,ModelGateway.StateExtraction> extractor,
            ModelGateway.Request request,ModelGateway.Generated candidate,StateExtractionPolicy policy) {
        ModelGateway.StateExtraction first;
        try {
            first=extractor.apply(request);
        } catch(Problem failure) {
            if(failure.getMessage()==null || !failure.getMessage().contains("模型返回多个 JSON 片段"))
                throw failure;
            ModelGateway.Request retry=retryRequest(request,
                    "上一次状态提取返回了多个 JSON 片段。只输出一个完整 JSON 对象，"
                    +"同时包含 facts、entities、relations 三个数组；不要补充第二个对象、解释或代码围栏。");
            return new Result(policy.validateAll(candidate,extractor.apply(retry)),null);
        }
        try {
            return new Result(policy.validateAll(candidate,first),null);
        } catch(Problem failure) {
            String feedback=retryGuidance(failure.getMessage());
            if(feedback==null) throw failure;
            ModelGateway.Request retry=retryRequest(request,
                    "上一次状态提取失败："+failure.getMessage()+"。这次只保留影响后续创作的少量状态；"+feedback);
            ModelGateway.StateExtraction second=extractor.apply(retry);
            try {
                return new Result(policy.validateAll(candidate,second),null);
            } catch(Problem repeated) {
                if(retryGuidance(repeated.getMessage())==null) throw repeated;
                StateExtractionPolicy.ValidatedState retained=policy.validateAllBestEffort(candidate,second);
                if(retained.facts().isEmpty() && retained.entities().isEmpty() && retained.relations().isEmpty())
                    throw repeated;
                return new Result(retained,"部分状态条目未通过原文证据或类型校验，已舍弃；正文和内容检查未受影响。如需补全档案，可单独重试状态提取。");
            }
        }
    }

    private static ModelGateway.Request retryRequest(ModelGateway.Request request,String feedback) {
        String instructions=(request.instructions()==null?"":request.instructions()+"\n")+feedback;
        return new ModelGateway.Request(request.action(),request.novel(),request.target(),request.context(),instructions);
    }

    private static String retryGuidance(String error) {
        if(error==null) return null;
        if(error.startsWith("状态证据并非正文原文："))
            return "每段 evidenceQuotes 必须从 candidate.content 原样复制，不得替换字词。"
                    +"无法找到逐字原文的条目请删除。";
        if(error.startsWith("状态条目类型不受支持："))
            return "facts 数组的 type 只能是 WORLD、CHARACTER、TIMELINE、EVENT、FORESHADOW；"
                    +"CONCEPT 只能用于 entities 数组中的实体。"
                    +"无法确定适合的事实类型时删除该事实，不得随意改成其他类型或改写正文。";
        if(error.contains("必须从人物或组织指向物品：")
                ||error.contains("人物关系两端都必须是人物：")
                ||error.contains("必须从人物指向组织：")
                ||error.contains("LOCATED_AT 必须指向地点：")
                ||error.contains("OWNS 的主体或对象类型不合法：")
                ||error.contains("关系两端必须引用本次 entities 中声明的实体："))
            return "只保留正文明确支持、且起点终点实体类型符合关系类型的关系。"
                    +"保管关系 CUSTODIAN_OF 必须从人物或组织指向 ITEM 物品；"
                    +"如果物品或关系无法从正文明确证明，就删除这条关系，不能为了通过校验虚构事实。"
                    +"facts、entities 和 relations 仍须同时返回；不要改动正文。";
        return null;
    }
}
