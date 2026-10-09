package com.novelforge.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import com.novelforge.novel.Novel.Fact;
import com.novelforge.novel.Novel.Plan;
import com.novelforge.outline.OutlineSpec;
import com.novelforge.shared.Problem;
import java.util.ArrayList;
import java.util.List;

/** Tolerate presentation wrappers and recoverable metadata omissions, never salvage nested/partial JSON. */
final class ModelJsonReader {
    static final int MAX_PATCH_OPERATIONS=100;
    private final ObjectMapper mapper;
    ModelJsonReader(ObjectMapper mapper) { this.mapper=mapper; }

    <T> T read(String text, Class<T> type) {
        text=text.strip().replaceFirst("^\uFEFF", "").strip();
        // Some compatible providers put reasoning in content. Only use the final answer after it.
        while (text.startsWith("<think>")) {
            int end=text.indexOf("</think>");
            if (end < 0) throw error("模型仅返回未结束的思考段，未提供最终 JSON");
            text=text.substring(end+8).strip();
        }
        if (text.isBlank()) throw error("模型未返回最终内容（content 为空）；不会把推理内容当作正文");
        String json=singleObject(text);
        try {
            JsonNode node=mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (type == Review.class) {
                require(node.path("passed").isBoolean(), "passed 必须是布尔值");
                for (String field : new String[]{"mainlineResolved", "endingClear", "foreshadowingResolved"}) {
                    JsonNode value=node.get(field);
                    if (value!=null && !value.isNull()) require(value.isBoolean(), field + " 必须是布尔值");
                }
                require(node.path("issues").isArray(), "issues 必须是数组");
                return type.cast(readReview(node));
            } else if (type == ChapterEvidenceAudit.class) {
                validateEvidenceAudit(node);
            } else if (type == RewritePatch.class) {
                normalizePatchPlanNumbers(node);
                validatePatch(node);
            } else if (type == ModelGateway.Generated.class) {
                strings(node, "title");
                List<String> draftIssues=new ArrayList<>();
                JsonNode summaryNode=node.path("summary");
                String summary=summaryNode.isTextual()?summaryNode.textValue():"";
                if (summary.isBlank()) draftIssues.add("内容摘要缺失或为空，请补全摘要后重新检查");
                JsonNode factsNode=node.path("facts");
                if (factsNode.isMissingNode() || factsNode.isNull())
                    draftIssues.add("档案增量缺失，请补充本次内容已经发生的事实变化后重新检查");
                else require(factsNode.isArray(), "facts 必须是数组（无事实时用 []）");
                if (factsNode.isArray()) for (JsonNode fact : factsNode) strings(fact, "key", "type", "detail", "state");
                require(!node.has("plan") || node.path("plan").isNull() || node.path("plan").isObject(), "plan 必须是对象或 null");
                require(!node.has("outlineSpec") || node.path("outlineSpec").isNull() || node.path("outlineSpec").isObject(),
                        "outlineSpec 必须是对象或 null");
                normalizePlanNumbers(node.path("plan"));
                if (node.path("plan").isObject()) validatePlan(node.path("plan"));
                List<Fact> facts=new ArrayList<>();
                if (factsNode.isArray()) for (JsonNode fact : factsNode) facts.add(mapper.treeToValue(fact,Fact.class));
                Plan plan=node.path("plan").isObject()?mapper.treeToValue(node.path("plan"),Plan.class):null;
                String content=node.path("content").isTextual()?node.path("content").textValue():"";
                if (content.isBlank() && plan!=null) content=renderPlan(plan);
                require(!content.isBlank(),"content 必须是非空文本");
                OutlineSpec outlineSpec=node.path("outlineSpec").isObject()
                        ?mapper.treeToValue(node.path("outlineSpec"),OutlineSpec.class):null;
                return type.cast(new ModelGateway.Generated(node.path("title").textValue(),content,
                        summary,facts,plan,outlineSpec,draftIssues));
            } else if (type == ModelGateway.StateExtraction.class) {
                require(node.path("facts").isArray(),"facts 必须是数组（没有状态变化时使用 []）");
                for(JsonNode fact:node.path("facts")) {
                    strings(fact,"key","type","detail","state");
                    require(fact.path("evidenceQuotes").isArray() && !fact.path("evidenceQuotes").isEmpty(),
                            "每条状态必须提供非空 evidenceQuotes 数组");
                    for(JsonNode quote:fact.path("evidenceQuotes"))
                        require(quote.isTextual() && !quote.asText().isBlank(),"evidenceQuotes 必须是非空文本");
                }
                JsonNode entities=node.path("entities");
                require(entities.isMissingNode()||entities.isArray(),"entities 必须是数组（没有实体时使用 []）");
                if(entities.isArray()) for(JsonNode entity:entities) {
                    strings(entity,"key","type","name","description");
                    require(entity.path("aliases").isArray(),"实体 aliases 必须是数组");
                    for(JsonNode alias:entity.path("aliases"))
                        require(alias.isTextual()&&!alias.asText().isBlank(),"实体 aliases 只能包含非空文本");
                    require(entity.path("evidenceQuotes").isArray()&&!entity.path("evidenceQuotes").isEmpty(),
                            "每个实体必须提供非空 evidenceQuotes 数组");
                }
                JsonNode relations=node.path("relations");
                require(relations.isMissingNode()||relations.isArray(),"relations 必须是数组（没有关系时使用 []）");
                if(relations.isArray()) for(JsonNode relation:relations) {
                    strings(relation,"key","fromEntityKey","type","toEntityKey","detail","state");
                    require(relation.path("evidenceQuotes").isArray()&&!relation.path("evidenceQuotes").isEmpty(),
                            "每条关系必须提供非空 evidenceQuotes 数组");
                }
            } else if (type == ModelGateway.DialogueResponse.class) {
                strings(node,"reply");
                require(node.path("decisionCandidates").isArray(),"decisionCandidates 必须是数组");
                for (JsonNode decision:node.path("decisionCandidates")) {
                    strings(decision,"type","text");
                    require(List.of("MUST_KEEP","MUST_CHANGE","FORBID","PREFERENCE","OPEN_QUESTION","ASSUMPTION")
                                    .contains(decision.path("type").asText()),
                            "decisionCandidates.type 不受支持");
                }
                JsonNode projectUpdates=node.path("projectUpdateCandidates");
                require(projectUpdates.isMissingNode()||projectUpdates.isArray(),"projectUpdateCandidates 必须是数组");
                if (projectUpdates.isArray()) for (JsonNode update:projectUpdates) {
                    strings(update,"field","proposedValue","reason");
                    require(List.of("TITLE","SYNOPSIS","REQUIREMENTS","TARGET_WORDS")
                                    .contains(update.path("field").asText()),
                            "projectUpdateCandidates.field 不受支持");
                }
                JsonNode proposal=node.path("actionProposal");
                require(proposal.isNull() || proposal.isMissingNode() || proposal.isObject(),
                        "actionProposal 必须是对象或 null");
                if (proposal.isObject()) {
                    strings(proposal,"operation","instructions");
                    require("GENERATE_OR_REVISE_OUTLINE".equals(proposal.path("operation").asText()),
                            "actionProposal.operation 不受支持");
                }
            } else {
                throw error("本地模型结果类型未配置解析规则");
            }
            return mapper.treeToValue(node, type);
        } catch (JsonProcessingException e) {
            throw error("模型 JSON 解析失败：语法、重复字段或字段类型不符合约定");
        }
    }

    private void validateEvidenceAudit(JsonNode node) {
        for (String field : List.of("planChecks","claimChecks")) {
            require(node.path(field).isArray(),field+" 必须是数组");
            for (JsonNode check : node.path(field)) {
                strings(check,"requirement");
                require(check.path("satisfied").isBoolean(),field+".satisfied 必须是布尔值");
                require(check.path("evidence").isArray(),field+".evidence 必须是数组");
                for (JsonNode evidence : check.path("evidence")) require(evidence.isTextual()&&!evidence.asText().isBlank(),field+".evidence 必须是非空文本");
            }
        }
    }

    private void validatePatch(JsonNode node) {
        require(node.path("operations").isArray() && !node.path("operations").isEmpty(), "operations 必须是非空数组");
        require(node.path("operations").size()<=MAX_PATCH_OPERATIONS,"operations 最多一百项");
        for (JsonNode operation : node.path("operations")) {
            strings(operation,"op");
            switch(operation.path("op").asText()) {
                case "REPLACE_TEXT" -> {
                    strings(operation,"field","oldText");
                    require(List.of("title","content","summary").contains(operation.path("field").asText()),"REPLACE_TEXT.field 不支持");
                    require(operation.path("newText").isTextual(),"REPLACE_TEXT.newText 必须是文本，可为空");
                }
                case "SET_FIELD" -> {
                    strings(operation,"field","value");
                    require(List.of("title","summary").contains(operation.path("field").asText()),"SET_FIELD 只能设置 title 或 summary");
                }
                case "SET_CONTENT" -> strings(operation,"value");
                case "UPSERT_FACT" -> {
                    require(operation.path("fact").isObject(),"UPSERT_FACT.fact 必须是对象");
                    strings(operation.path("fact"),"key","type","detail","state");
                }
                case "DELETE_FACT" -> strings(operation,"key");
                case "REPLACE_FACTS" -> {
                    require(operation.path("facts").isArray(),"REPLACE_FACTS.facts 必须是数组");
                    for (JsonNode fact : operation.path("facts")) strings(fact,"key","type","detail","state");
                }
                case "SET_PLAN" -> {
                    require(operation.path("plan").isObject(),"SET_PLAN.plan 必须是对象");
                    validatePlan(operation.path("plan"));
                }
                default -> require(false,"未知修订操作 "+operation.path("op").asText());
            }
        }
    }

    private Review readReview(JsonNode node) {
        List<String> texts=new ArrayList<>(); List<ReviewIssue> details=new ArrayList<>();
        boolean objects=false, strings=false;
        String defaultSeverity=node.path("passed").asBoolean()?"建议优化":"必须修正";
        for (JsonNode issue : node.path("issues")) {
            if (issue.isTextual()) { strings=true; texts.add(issue.asText()); }
            else if (issue.isObject()) { objects=true; ReviewIssue detail=readIssue(issue,defaultSeverity); details.add(detail); texts.add(detail.text()); }
            else require(false,"issues 条目必须是结构化问题或旧版文本");
        }
        require(!(objects&&strings),"issues 不能混用结构化问题和旧版文本");
        if (strings && node.path("issueDetails").isArray()) {
            for (JsonNode detail : node.path("issueDetails")) details.add(readIssue(detail,defaultSeverity));
        }
        if (strings && details.size()!=texts.size()) details.clear();
        boolean passed=node.path("passed").asBoolean();
        if (objects) passed=details.stream().noneMatch(issue->"必须修正".equals(issue.severity()));
        return new Review(passed,texts,node.path("mainlineResolved").asBoolean(),
                node.path("endingClear").asBoolean(),node.path("foreshadowingResolved").asBoolean(),details.isEmpty()&& !texts.isEmpty()?null:details);
    }
    private ReviewIssue readIssue(JsonNode issue,String defaultSeverity) {
        strings(issue,"location","problem","evidence");
        JsonNode suggestionNode=issue.get("suggestion");
        require(suggestionNode==null||suggestionNode.isNull()||suggestionNode.isTextual(),
                "suggestion 必须是文本");
        String suggestion=suggestionNode==null||suggestionNode.isNull()?"":suggestionNode.asText();
        String severity=issue.path("severity").isTextual()&&!issue.path("severity").asText().isBlank()
                ?issue.path("severity").asText():defaultSeverity;
        require(List.of("必须修正","作者决定","建议优化").contains(severity),
                "问题严重程度只能是“必须修正”“作者决定”或“建议优化”");
        return new ReviewIssue(issue.path("location").asText(),issue.path("problem").asText(),issue.path("evidence").asText(),
                suggestion,severity);
    }

    private String singleObject(String text) {
        int start=-1, depth=0, candidateEnd=-1, extraClosing=-1;
        boolean quoted=false, escaped=false;
        String candidate=null;
        for (int i=0; i<text.length(); i++) {
            char c=text.charAt(i);
            if (depth==0) {
                if (c=='}' || c==']') {
                    // A frequent provider formatting defect is one duplicated root closing brace:
                    // { ... }}. It is safe only after the one complete object and immediately
                    // adjacent to it (apart from whitespace). Everything else remains ambiguous.
                    if (candidate!=null && c=='}' && extraClosing<0
                            && text.substring(candidateEnd+1,i).isBlank()) {
                        extraClosing=i;
                        continue;
                    }
                    throw error("模型 JSON 存在多余或无法唯一确定的结束括号");
                }
                if (c!='{' && c!='[') continue;
                if (candidate!=null) throw error("模型返回多个 JSON 片段，无法确定修订版本；请要求只返回一个完整对象");
                if (c=='[') throw error("模型 JSON 顶层必须是对象，不能是数组");
                start=i; depth=1; continue;
            }
            if (quoted) {
                if (escaped) escaped=false;
                else if (c=='\\') escaped=true;
                else if (c=='\"') quoted=false;
            } else if (c=='\"') quoted=true;
            else if (c=='{' || c=='[') depth++;
            else if (c=='}' || c==']') {
                if (--depth==0) { candidate=text.substring(start, i+1); candidateEnd=i; }
            }
        }
        if (depth!=0) throw error("模型 JSON 不完整或字符串未闭合，请检查输出额度");
        if (candidate==null) throw error("模型返回普通文本，未找到约定 JSON；生成和检查均须遵循各自的输出结构");
        return candidate;
    }

    private void normalizePatchPlanNumbers(JsonNode node) {
        if (!node.path("operations").isArray()) return;
        for (JsonNode operation : node.path("operations"))
            if ("SET_PLAN".equals(operation.path("op").asText())) normalizePlanNumbers(operation.path("plan"));
    }

    /** Normalize only exact chapter ordinals in known integer fields; never guess arbitrary text. */
    private void normalizePlanNumbers(JsonNode plan) {
        if (!(plan instanceof ObjectNode object)) return;
        for (String field : List.of("startChapter","endChapter","prepareNextAfterChapter"))
            normalizeChapterOrdinal(object,field);
        if (object.path("chapters").isArray()) for (JsonNode chapter : object.path("chapters"))
            if (chapter instanceof ObjectNode chapterObject) normalizeChapterOrdinal(chapterObject,"number");
    }

    private void normalizeChapterOrdinal(ObjectNode object,String field) {
        JsonNode value=object.get(field);
        if (value==null || !value.isTextual()) return;
        Integer number=parseExactChapterOrdinal(value.asText());
        if (number!=null) object.put(field,number);
    }

    private Integer parseExactChapterOrdinal(String raw) {
        String value=raw==null?"":raw.strip();
        if (!value.startsWith("第") || !value.endsWith("章") || value.length()<3) return null;
        String number=value.substring(1,value.length()-1);
        String ascii=toAsciiDigits(number);
        try {
            if (ascii.matches("[1-9][0-9]*")) {
                long parsed=Long.parseLong(ascii);
                return parsed<=Integer.MAX_VALUE?(int)parsed:null;
            }
        } catch (NumberFormatException ignored) { return null; }
        Integer parsed=parseCanonicalChineseNumber(number.replace('〇','零'));
        return parsed!=null && chineseNumber(parsed).equals(number.replace('〇','零')) ? parsed : null;
    }

    private Integer parseCanonicalChineseNumber(String value) {
        if (value.isBlank()) return null;
        int total=0,current=-1,lastUnit=10_000;
        for (int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            int digit="零一二三四五六七八九".indexOf(c);
            if (digit>=0) { current=digit; continue; }
            int unit=switch(c) { case '十' -> 10; case '百' -> 100; case '千' -> 1000; default -> -1; };
            if (unit<0 || unit>=lastUnit) return null;
            total+=(current<0?1:current)*unit; current=-1; lastUnit=unit;
        }
        if (current>=0) total+=current;
        return total>0 && total<=9999?total:null;
    }

    private String chineseNumber(int value) {
        if (value<1 || value>9999) return "";
        String digits="零一二三四五六七八九";
        int[] units={1000,100,10,1}; String[] names={"千","百","十",""};
        StringBuilder result=new StringBuilder(); boolean zero=false;
        for (int i=0;i<units.length;i++) {
            int digit=value/units[i]; value%=units[i];
            if (digit==0) { if (!result.isEmpty() && value>0) zero=true; continue; }
            if (zero) { result.append('零'); zero=false; }
            if (!(units[i]==10 && digit==1 && result.isEmpty())) result.append(digits.charAt(digit));
            result.append(names[i]);
        }
        return result.toString();
    }

    private String toAsciiDigits(String value) {
        StringBuilder result=new StringBuilder(value.length());
        for (char c:value.toCharArray()) result.append(c>='０'&&c<='９'?(char)('0'+c-'０'):c);
        return result.toString();
    }

    private void validatePlan(JsonNode plan) {
        for (String field : new String[]{"startChapter", "endChapter", "prepareNextAfterChapter"})
            require(plan.path(field).isIntegralNumber() && plan.path(field).canConvertToInt(), "plan." + field + " 必须是整数");
        require(plan.path("finalBatch").isBoolean(), "plan.finalBatch 必须是布尔值");
        strings(plan, "triggerReason", "handoff");
        require(plan.path("assumptions").isTextual(), "plan.assumptions 必须是文本");
        require(plan.path("chapters").isArray(), "plan.chapters 必须是数组");
        for (JsonNode chapter : plan.path("chapters")) {
            require(chapter.path("number").isIntegralNumber() && chapter.path("number").canConvertToInt(), "章节 number 必须是整数");
            strings(chapter, "title", "purpose");
            if (chapter.has("targetWords") && !chapter.path("targetWords").isNull())
                require(chapter.path("targetWords").isIntegralNumber() && chapter.path("targetWords").asInt()>=100
                        && chapter.path("targetWords").asInt()<=10000,"章节 targetWords 必须是100至10000的整数");
            if (chapter.has("sceneBeats") && !chapter.path("sceneBeats").isNull()) {
                // An empty array is the serialized form of a legacy three-field
                // ChapterBeat and intentionally falls back to the purpose text.
                require(chapter.path("sceneBeats").isArray() && chapter.path("sceneBeats").size()<=4,
                        "章节 sceneBeats 最多包含4个场景节点");
                for (JsonNode scene : chapter.path("sceneBeats")) require(scene.isTextual()&&!scene.asText().isBlank(),"场景节点必须是非空文本");
            }
            for (String field : List.of("revealBoundary","endingHook"))
                if (chapter.has(field) && !chapter.path(field).isNull())
                    require(chapter.path(field).isTextual(),"章节 "+field+" 必须是文本");
        }
    }
    private String renderPlan(Plan plan) {
        StringBuilder text=new StringBuilder("第").append(plan.startChapter).append("章至第")
                .append(plan.endChapter).append("章章节规划");
        for (var chapter:plan.chapters) {
            text.append("\n\n第").append(chapter.number()).append("章 ").append(chapter.title())
                    .append("\n核心变化：").append(chapter.purpose());
            if (chapter.targetWords()!=null) text.append("\n建议字数：").append(chapter.targetWords()).append("字");
            if (!chapter.sceneBeats().isEmpty()) {
                text.append("\n场景节点：");
                for (int i=0;i<chapter.sceneBeats().size();i++)
                    text.append("\n").append(i+1).append(". ").append(chapter.sceneBeats().get(i));
            }
            if (!chapter.revealBoundary().isBlank()) text.append("\n揭示边界：").append(chapter.revealBoundary());
            if (!chapter.endingHook().isBlank()) text.append("\n章末局面：").append(chapter.endingHook());
        }
        text.append("\n\n批次衔接：").append(plan.handoff);
        if (!plan.triggerReason.isBlank()) text.append("\n下一批触发：").append(plan.triggerReason);
        if (!plan.assumptions.isBlank()) text.append("\n待复核假设：").append(plan.assumptions);
        return text.toString();
    }
    private void strings(JsonNode node, String... fields) {
        for (String field : fields)
            require(node.path(field).isTextual() && !node.path(field).textValue().isBlank(), field + " 必须是非空文本");
    }
    private void require(boolean condition, String detail) {
        if (!condition) throw error("模型 JSON 缺少必要字段或类型错误：" + detail);
    }
    private Problem error(String message) { return new Problem(502, message + "；原有内容未覆盖，未自动重试"); }
}
