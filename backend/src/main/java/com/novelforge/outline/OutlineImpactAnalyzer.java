package com.novelforge.outline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Selects the minimum safe professional rechecks after one structured outline repair. */
@Component
public class OutlineImpactAnalyzer {
    public record ReviewScope(boolean continuityRequired,boolean plotRequired,List<String> changedSections) {
        public ReviewScope {
            changedSections=changedSections==null?List.of():List.copyOf(changedSections);
        }
    }

    private final ObjectMapper mapper;

    public OutlineImpactAnalyzer(ObjectMapper mapper) { this.mapper=mapper; }

    public ReviewScope analyze(OutlineSpec before,OutlineSpec after) {
        if (before==null || after==null) return new ReviewScope(true,true,List.of("结构化大纲整体"));
        List<String> changed=new ArrayList<>();
        boolean continuity=false,plot=false;
        if (different(before.schemaVersion,after.schemaVersion)) {
            changed.add("结构版本"); continuity=true; plot=true;
        }
        if (different(before.storyCore,after.storyCore)) {
            changed.add("故事核心"); continuity=true; plot=true;
        }
        if (different(before.genreTone,after.genreTone)) changed.add("题材与基调");
        if (different(before.protagonistGoal,after.protagonistGoal)) {
            changed.add("主角目标"); continuity=true; plot=true;
        }
        if (different(before.coreConflict,after.coreConflict)) {
            changed.add("核心冲突"); continuity=true; plot=true;
        }
        if (different(before.endingContract,after.endingContract)) {
            changed.add("明确结局"); continuity=true; plot=true;
        }
        if (different(before.requirements,after.requirements)) changed.add("需求追踪");
        if (different(before.worldRules,after.worldRules)) {
            changed.add("世界规则"); continuity=true;
        }
        if (different(before.characters,after.characters)) {
            changed.add("人物骨架"); continuity=true; plot=true;
        }
        if (different(before.parts,after.parts)) {
            changed.add("篇章、阶段或事件"); continuity=true; plot=true;
        }
        if (different(before.foreshadows,after.foreshadows)) {
            changed.add("伏笔"); plot=true;
        }
        if (different(before.threads,after.threads)) {
            changed.add("支线"); plot=true;
        }
        if (different(before.constraints,after.constraints)) {
            changed.add("大纲约束"); continuity=true; plot=true;
        }
        if (different(before.creativeAssumptions,after.creativeAssumptions)) changed.add("创作假设");
        return new ReviewScope(continuity,plot,changed);
    }

    private boolean different(Object before,Object after) {
        return !mapper.valueToTree(before).equals(mapper.valueToTree(after));
    }
}
