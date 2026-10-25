package com.novelforge.generation;

import com.novelforge.shared.Problem;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
            String guidance=extractionFormatGuidance(failure.getMessage());
            if(guidance==null) throw failure;
            ModelGateway.Request retry=retryRequest(request,guidance);
            return new Result(policy.validateAll(candidate,extractor.apply(retry)),null);
        }
        try {
            return new Result(policy.validateAll(candidate,first),null);
        } catch(Problem failure) {
            String feedback=retryGuidance(failure.getMessage());
            if(feedback==null) throw failure;
            StateExtractionPolicy.ValidatedState firstRetained=policy.validateAllBestEffort(candidate,first);
            ModelGateway.Request retry=retryRequest(request,
                    "上一次状态提取失败："+failure.getMessage()+"。这次优先修复该编号的引文或类型，"
                    +"其他条目仅在有正文证据时保留；"+feedback
                    +evidenceHints(candidate,first,failure.getMessage()));
            ModelGateway.StateExtraction second=extractor.apply(retry);
            try {
                StateExtractionPolicy.ValidatedState merged=merge(firstRetained,policy.validateAll(candidate,second));
                return new Result(merged,containsKey(merged,failedKey(failure.getMessage()))?null:
                        "未找到足以修复的正文证据，已舍弃该状态条目；正文和内容检查未受影响。如需补全档案，可单独重试状态提取。");
            } catch(Problem repeated) {
                if(retryGuidance(repeated.getMessage())==null) throw repeated;
                StateExtractionPolicy.ValidatedState retained=merge(firstRetained,
                        policy.validateAllBestEffort(candidate,second));
                if(retained.facts().isEmpty() && retained.entities().isEmpty() && retained.relations().isEmpty())
                    throw repeated;
                return new Result(retained,"部分状态条目未通过原文证据或类型校验，已舍弃；正文和内容检查未受影响。如需补全档案，可单独重试状态提取。");
            }
        }
    }

    private static String extractionFormatGuidance(String error) {
        if(error==null) return null;
        if(error.contains("模型返回多个 JSON 片段"))
            return "上一次状态提取返回了多个 JSON 片段。只输出一个完整 JSON 对象，"
                    +"同时包含 facts、entities、relations 三个数组；不要补充第二个对象、解释或代码围栏。";
        if(error.contains("模型 JSON 缺少必要字段或类型错误"))
            return "上一次状态提取的 JSON 字段缺失或类型错误。重新输出一个完整对象，包含 facts、entities、relations 数组；"
                    +"每条关系的 fromEntityKey 和 toEntityKey 必须是非空的实体编号，且两个编号都要在 entities 中声明。"
                    +"不能确定关系两端时删除该关系，不要填空字符串，也不要修改章节正文。";
        return null;
    }

    private static String failedKey(String error) {
        if(error==null || !error.contains("：")) return "";
        String value=error.substring(error.indexOf('：')+1);
        int end=value.indexOf('（');
        return (end<0?value:value.substring(0,end)).strip();
    }

    private static boolean containsKey(StateExtractionPolicy.ValidatedState state,String key) {
        if(key.isBlank()) return true;
        return state.facts().stream().anyMatch(item->key.equals(item.key()))
                ||state.entities().stream().anyMatch(item->key.equals(item.key()))
                ||state.relations().stream().anyMatch(item->key.equals(item.key()));
    }

    /** Local keyword search supplies possible verbatim spans, never an asserted relationship or fabricated quote. */
    private static String evidenceHints(ModelGateway.Generated candidate,ModelGateway.StateExtraction first,String error) {
        String key=failedKey(error);
        if(key.isBlank() || candidate==null || candidate.content()==null) return "";
        Set<String> keywords=new LinkedHashSet<>();
        if(first.facts()!=null) first.facts().stream().filter(item->item!=null && key.equals(item.key()))
                .forEach(item->keywords.addAll(shortKeywords(item.detail())));
        if(first.entities()!=null) first.entities().stream().filter(item->item!=null && key.equals(item.key()))
                .forEach(item->{ if(item.name()!=null) keywords.add(item.name()); keywords.addAll(shortKeywords(item.description())); });
        if(first.relations()!=null) first.relations().stream().filter(item->item!=null && key.equals(item.key()))
                .forEach(item->{
                    keywords.addAll(shortKeywords(item.detail()));
                    if(first.entities()!=null) first.entities().stream().filter(entity->entity!=null
                            && (Objects.equals(entity.key(),item.fromEntityKey()) || Objects.equals(entity.key(),item.toEntityKey())))
                            .forEach(entity->{ if(entity.name()!=null) keywords.add(entity.name()); });
                });
        keywords.removeIf(word->word==null || word.length()<2);
        if(keywords.isEmpty()) return "";
        List<String> sentences=new ArrayList<>();
        for(String sentence:candidate.content().split("(?<=[。！？；\\n])")) {
            String clean=sentence.strip();
            if(clean.length()>=2 && clean.length()<=160) sentences.add(clean);
        }
        List<String> matches=sentences.stream().filter(sentence->keywords.stream().anyMatch(sentence::contains))
                .sorted(Comparator.<String>comparingInt(sentence->(int)keywords.stream().filter(sentence::contains).count())
                        .reversed()).distinct().limit(3).toList();
        if(matches.isEmpty()) return "\n本地关键词检索没有找到明显候选句；请换词搜索原文，找不到就删除该条。";
        return "\n本地按条目关键词检索到以下原文候选句（仅供重新核对，不能自动视为证据）："
                +String.join(" | ",matches)+"。必须检查它是否真正支持该事实或关系；不支持就删除该条。";
    }

    private static List<String> shortKeywords(String detail) {
        if(detail==null) return List.of();
        String[] pieces=detail.split("[^\\p{IsHan}A-Za-z0-9]+");
        List<String> result=new ArrayList<>();
        for(String piece:pieces) if(piece.length()>=2) {
            result.add(piece.length()<=6?piece:piece.substring(0,3));
            if(piece.length()>6) result.add(piece.substring(piece.length()-3));
        }
        return result;
    }

    private static ModelGateway.Request retryRequest(ModelGateway.Request request,String feedback) {
        String instructions=(request.instructions()==null?"":request.instructions()+"\n")+feedback;
        return new ModelGateway.Request(request.action(),request.novel(),request.target(),request.context(),instructions);
    }

    /** Validated entries from the first pass survive a focused retry that only returns repaired entries. */
    private static StateExtractionPolicy.ValidatedState merge(StateExtractionPolicy.ValidatedState first,
            StateExtractionPolicy.ValidatedState second) {
        Map<String,com.novelforge.novel.Novel.Fact> facts=new LinkedHashMap<>();
        Map<String,com.novelforge.novel.Novel.StateEntity> entities=new LinkedHashMap<>();
        Map<String,com.novelforge.novel.Novel.StateRelation> relations=new LinkedHashMap<>();
        Map<String,com.novelforge.novel.Novel.StateEvidence> evidence=new LinkedHashMap<>();
        first.facts().forEach(item->facts.put(item.key(),item));
        first.entities().forEach(item->entities.put(item.key(),item));
        first.relations().forEach(item->relations.put(item.key(),item));
        first.evidence().forEach(item->evidence.put(item.key(),item));
        second.facts().forEach(item->facts.putIfAbsent(item.key(),item));
        second.entities().forEach(item->entities.putIfAbsent(item.key(),item));
        second.relations().forEach(item->relations.putIfAbsent(item.key(),item));
        second.evidence().forEach(item->evidence.putIfAbsent(item.key(),item));
        return new StateExtractionPolicy.ValidatedState(List.copyOf(facts.values()),List.copyOf(entities.values()),
                List.copyOf(relations.values()),List.copyOf(evidence.values()));
    }

    private static String retryGuidance(String error) {
        if(error==null) return null;
        if(error.startsWith("状态证据长度必须在 2 到 ")
                ||error.startsWith("提取条目缺少正文原文证据：")
                ||error.startsWith("状态条目缺少正文原文证据："))
            return "先在 candidate.content 中搜索该条目的主体名和动作关键词，选能支持关系或事实的完整短句逐字复制；"
                    +"如这一句不合适，换另一句真实原文，不得只引用一个字或拼接片段。"
                    +"找不到充分原文就删除该条目；不要改动正文。";
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
