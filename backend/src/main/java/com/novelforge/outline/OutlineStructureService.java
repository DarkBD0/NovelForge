package com.novelforge.outline;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.generation.ModelGateway;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import com.novelforge.novel.Novel.Version;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Deterministic normalization, rendering, hashing and reference validation for outlines. */
@Component
public class OutlineStructureService {
    public static final String VERSION="2026-09-29-outline-spec-v1";
    private final OutlineRenderer renderer;
    private final ObjectMapper canonicalMapper;

    public OutlineStructureService(OutlineRenderer renderer,ObjectMapper mapper) {
        this.renderer=renderer;
        this.canonicalMapper=mapper.copy().configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true);
    }

    public ModelGateway.Generated normalizeGenerated(Novel novel,ModelGateway.Generated generated) {
        if (generated.outlineSpec()==null) {
            List<String> issues=new ArrayList<>(generated.draftIssues());
            issues.add("结构化大纲缺失，请重新生成完整 OutlineSpec 后再检查");
            return new ModelGateway.Generated(generated.title(),generated.content(),generated.summary(),
                    generated.facts(),generated.plan(),null,issues);
        }
        String rendered=renderer.render(generated.outlineSpec());
        return new ModelGateway.Generated(generated.title(),rendered,generated.summary(),generated.facts(),
                generated.plan(),generated.outlineSpec(),generated.draftIssues());
    }

    public String render(OutlineSpec spec) { return renderer.render(spec); }

    public Review validate(Novel novel,ModelGateway.Generated candidate,Version storedVersion) {
        OutlineSpec spec=candidate.outlineSpec();
        if (spec==null) return new Review(true,List.of(),false,false,false,List.of());
        List<ReviewIssue> issues=new ArrayList<>();
        required(issues,"故事核心",spec.storyCore); required(issues,"题材与叙事基调",spec.genreTone);
        required(issues,"主角目标",spec.protagonistGoal); required(issues,"核心冲突",spec.coreConflict);
        required(issues,"明确结局",spec.endingContract);
        nonEmpty(issues,"世界规则",spec.worldRules); nonEmpty(issues,"核心人物骨架",spec.characters);
        nonEmpty(issues,"全书结构",spec.parts);

        Set<String> ids=new HashSet<>(); Set<String> stageIds=new HashSet<>(); Set<String> requirementIds=new HashSet<>();
        for (OutlineSpec.RequirementTrace item:safe(spec.requirements)) {
            id(issues,ids,"需求追踪",item.id); if (notBlank(item.id)) requirementIds.add(item.id);
            required(issues,"需求追踪条目",item.text);
            if (!List.of("EXPLICIT","ASSUMPTION").contains(item.type)) issue(issues,"需求追踪",
                    "需求类型必须是明确要求或创作假设","当前类型："+value(item.type),"将类型改为 EXPLICIT 或 ASSUMPTION");
        }
        for (OutlineSpec.WorldRule rule:safe(spec.worldRules)) {
            id(issues,ids,"世界规则",rule.id); required(issues,"世界规则",rule.rule);
            required(issues,"世界规则适用范围",rule.scope); required(issues,"世界规则限制",rule.limit);
            if (!List.of("HARD","DIRECTION","OPEN").contains(rule.constraintLevel)) issue(issues,"世界规则",
                    "约束等级无效","当前等级："+value(rule.constraintLevel),"使用 HARD、DIRECTION 或 OPEN");
            if (!"CANDIDATE".equals(rule.authorityStatus)) issue(issues,"世界规则",
                    "未确认大纲中的规则不能标记为已确认","当前权威状态："+value(rule.authorityStatus),"生成阶段统一标记为 CANDIDATE");
        }
        for (OutlineSpec.CharacterArc person:safe(spec.characters)) {
            id(issues,ids,"核心人物骨架",person.id); required(issues,"核心人物姓名",person.name);
            required(issues,"人物目标",person.goal); required(issues,"人物动机",person.motivation);
            required(issues,"人物剧情功能",person.storyFunction); required(issues,"人物最终状态",person.finalState);
        }

        long partWords=0;
        for (OutlineSpec.StoryPart part:safe(spec.parts)) {
            id(issues,ids,"全书结构",part.id); required(issues,"篇章标题",part.title);
            required(issues,"篇章目标",part.goal); required(issues,"篇章冲突",part.conflict);
            if (part.estimatedWords<=0) issue(issues,"全书结构 > "+value(part.title),"篇章预计字数必须大于零",
                    "当前值："+part.estimatedWords,"填写可汇总的正整数字数");
            partWords+=Math.max(0,part.estimatedWords);
            nonEmpty(issues,"全书结构 > "+value(part.title),part.stages);
            long stageWords=0;
            for (OutlineSpec.StoryStage stage:safe(part.stages)) {
                id(issues,ids,"故事阶段",stage.id); if (notBlank(stage.id)) stageIds.add(stage.id);
                required(issues,"故事阶段标题",stage.title); required(issues,"故事阶段目标",stage.goal);
                if (stage.estimatedWords<=0) issue(issues,"故事阶段 > "+value(stage.title),"阶段预计字数必须大于零",
                        "当前值："+stage.estimatedWords,"填写可汇总的正整数字数");
                stageWords+=Math.max(0,stage.estimatedWords);
                nonEmpty(issues,"故事阶段 > "+value(stage.title),stage.events);
                for (OutlineSpec.KeyEvent event:safe(stage.events)) {
                    id(issues,ids,"关键事件",event.id); required(issues,"关键事件",event.event);
                    required(issues,"关键事件后果",event.consequence);
                }
            }
            if (part.estimatedWords>0 && stageWords!=part.estimatedWords) issue(issues,"全书结构 > "+value(part.title),
                    "阶段字数之和与篇章预算不一致","阶段合计 "+stageWords+" 字，篇章预算 "+part.estimatedWords+" 字",
                    "调整阶段预算，使其与篇章预算相等");
        }
        if (novel.targetWords>0 && partWords!=novel.targetWords) issue(issues,"全书结构 > 字数预算",
                "各篇章预算之和与小说目标字数不一致","篇章合计 "+partWords+" 字，目标 "+novel.targetWords+" 字",
                "调整篇章预算，使总和等于目标字数；目标字数仍只是创作参考");

        for (OutlineSpec.Foreshadow clue:safe(spec.foreshadows)) {
            id(issues,ids,"重要伏笔",clue.id); required(issues,"重要伏笔",clue.clue);
            reference(issues,"重要伏笔 > "+value(clue.clue),"铺设阶段",clue.setupStageId,stageIds);
            for (String stageId:safe(clue.developmentStageIds)) reference(issues,"重要伏笔 > "+value(clue.clue),"发展阶段",stageId,stageIds);
            reference(issues,"重要伏笔 > "+value(clue.clue),"回收阶段",clue.recoveryStageId,stageIds);
        }
        for (OutlineSpec.StoryThread thread:safe(spec.threads)) {
            id(issues,ids,"主要支线",thread.id); required(issues,"主要支线",thread.title);
            required(issues,"支线服务目标",thread.serviceGoal);
            reference(issues,"主要支线 > "+value(thread.title),"收束阶段",thread.resolutionStageId,stageIds);
        }
        for (OutlineSpec.Constraint constraint:safe(spec.constraints)) {
            id(issues,ids,"约束",constraint.id); required(issues,"约束内容",constraint.text);
            if (!List.of("HARD","DIRECTION","OPEN").contains(constraint.category)) issue(issues,"大纲约束",
                    "约束分类无效","当前分类："+value(constraint.category),"使用 HARD、DIRECTION 或 OPEN");
            if (notBlank(constraint.sourceRequirementId) && !requirementIds.contains(constraint.sourceRequirementId))
                issue(issues,"大纲约束","约束引用了不存在的需求编号","引用编号："+constraint.sourceRequirementId,
                        "改为 requirements 中实际存在的编号，或移除错误引用");
        }
        for (OutlineSpec.StoryPart part:safe(spec.parts)) for (OutlineSpec.StoryStage stage:safe(part.stages))
            for (String prerequisite:safe(stage.prerequisiteIds)) if (!ids.contains(prerequisite))
                issue(issues,"故事阶段 > "+value(stage.title),"前置条件引用不存在",
                        "引用编号："+value(prerequisite),"改为当前 OutlineSpec 中存在的阶段或事件编号");

        String rendered=renderer.render(spec);
        if (!rendered.equals(candidate.content())) issue(issues,"作者可读大纲",
                "展示文本不是由当前结构化大纲生成","当前展示文本与确定性投影不一致",
                "重新从当前 OutlineSpec 生成作者可读大纲");
        if (storedVersion!=null && storedVersion.outlineSpecHash!=null && !storedVersion.outlineSpecHash.equals(hash(spec)))
            issue(issues,"结构化大纲","结构哈希与当前结构不一致","保存的结构哈希无法验证当前 OutlineSpec",
                    "重新保存结构化大纲并使检查绑定新版本");
        boolean passed=issues.stream().noneMatch(item->"必须修正".equals(item.severity()));
        return new Review(passed,issues.stream().map(ReviewIssue::text).toList(),false,false,false,issues);
    }

    public String hash(OutlineSpec spec) {
        try {
            byte[] json=canonicalMapper.writeValueAsBytes(spec);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception e) { throw new IllegalStateException("无法计算结构化大纲哈希",e); }
    }

    private void id(List<ReviewIssue> issues,Set<String> ids,String location,String id) {
        if (!notBlank(id)) { issue(issues,location,"缺少稳定编号","结构条目没有编号","为条目添加本版本内唯一的稳定编号"); return; }
        if (!ids.add(id)) issue(issues,location,"稳定编号重复","重复编号："+id,"为重复条目分配不同编号");
    }
    private void reference(List<ReviewIssue> issues,String location,String kind,String id,Set<String> valid) {
        if (!notBlank(id) || !valid.contains(id)) issue(issues,location,kind+"引用不存在",
                "引用编号："+value(id),"改为实际存在的故事阶段编号");
    }
    private void required(List<ReviewIssue> issues,String location,String value) {
        if (!notBlank(value)) issue(issues,"结构化大纲 > "+location,"缺少必要内容","当前字段为空","补充该项后重新检查");
    }
    private void nonEmpty(List<ReviewIssue> issues,String location,List<?> values) {
        if (values==null||values.isEmpty()) issue(issues,"结构化大纲 > "+location,"缺少必要结构","当前列表为空","至少补充一项");
    }
    private void issue(List<ReviewIssue> issues,String location,String problem,String evidence,String suggestion) {
        issues.add(new ReviewIssue(location,problem,evidence,suggestion,"必须修正"));
    }
    private boolean notBlank(String value) { return value!=null&&!value.isBlank(); }
    private String value(String value) { return notBlank(value)?value:"空"; }
    private <T> List<T> safe(List<T> values) { return values==null?List.of():values; }
}
