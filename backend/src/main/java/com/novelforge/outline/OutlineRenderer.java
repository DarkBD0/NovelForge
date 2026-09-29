package com.novelforge.outline;

import org.springframework.stereotype.Component;

/** Produces the only author-readable outline projection from an OutlineSpec. */
@Component
public class OutlineRenderer {
    public String render(OutlineSpec spec) {
        StringBuilder out=new StringBuilder();
        section(out,"故事核心",spec.storyCore);
        section(out,"题材与叙事基调",spec.genreTone);
        section(out,"主角目标",spec.protagonistGoal);
        section(out,"核心冲突",spec.coreConflict);
        section(out,"明确结局",spec.endingContract);

        out.append("\n## 世界规则\n");
        for (OutlineSpec.WorldRule rule:safe(spec.worldRules)) {
            out.append("- ").append(text(rule.rule));
            appendDetail(out,"适用范围",rule.scope); appendDetail(out,"限制",rule.limit);
            appendDetail(out,"代价",rule.cost); appendDetail(out,"例外",rule.exceptionRule);
            out.append('\n');
        }

        out.append("\n## 核心人物骨架\n");
        for (OutlineSpec.CharacterArc person:safe(spec.characters)) {
            out.append("### ").append(text(person.name)).append('\n');
            line(out,"剧情功能",person.storyFunction); line(out,"核心目标",person.goal);
            line(out,"深层动机",person.motivation); line(out,"关键缺陷",person.flaw);
            line(out,"关键选择",person.keyChoice); line(out,"变化方向",person.arcDirection);
            line(out,"最终状态",person.finalState);
        }

        out.append("\n## 全书结构\n");
        int partNumber=0;
        for (OutlineSpec.StoryPart part:safe(spec.parts)) {
            partNumber++;
            out.append("### 第").append(partNumber).append("部分：").append(text(part.title));
            if (part.estimatedWords>0) out.append("（预计").append(part.estimatedWords).append("字）");
            out.append('\n');
            line(out,"阶段目标",part.goal); line(out,"主要冲突",part.conflict); line(out,"关键转折",part.turn);
            int stageNumber=0;
            for (OutlineSpec.StoryStage stage:safe(part.stages)) {
                stageNumber++;
                out.append("#### ").append(partNumber).append('.').append(stageNumber).append(' ')
                        .append(text(stage.title));
                if (stage.estimatedWords>0) out.append("（预计").append(stage.estimatedWords).append("字）");
                out.append('\n');
                line(out,"目标",stage.goal);
                for (OutlineSpec.KeyEvent event:safe(stage.events)) {
                    out.append("- ").append(text(event.title)).append("：").append(text(event.event));
                    appendDetail(out,"后果",event.consequence); appendDetail(out,"人物变化",event.characterChange);
                    appendDetail(out,"不可逆转折",event.irreversibleTurn); out.append('\n');
                }
            }
        }

        out.append("\n## 重要伏笔\n");
        for (OutlineSpec.Foreshadow clue:safe(spec.foreshadows))
            out.append("- ").append(text(clue.clue)).append("；服务目标：").append(text(clue.serviceGoal)).append('\n');

        if (!safe(spec.threads).isEmpty()) {
            out.append("\n## 主要支线\n");
            for (OutlineSpec.StoryThread thread:safe(spec.threads))
                out.append("- ").append(text(thread.title)).append("：").append(text(thread.serviceGoal)).append('\n');
        }

        if (!safe(spec.creativeAssumptions).isEmpty()) {
            out.append("\n## 创作假设\n");
            for (String assumption:safe(spec.creativeAssumptions)) out.append("- ").append(text(assumption)).append('\n');
        }
        return out.toString().strip();
    }

    private void section(StringBuilder out,String title,String value) {
        out.append("## ").append(title).append('\n').append(text(value)).append("\n");
    }
    private void line(StringBuilder out,String label,String value) {
        if (value!=null&&!value.isBlank()) out.append("- ").append(label).append("：").append(value.strip()).append('\n');
    }
    private void appendDetail(StringBuilder out,String label,String value) {
        if (value!=null&&!value.isBlank()) out.append("；").append(label).append("：").append(value.strip());
    }
    private String text(String value) { return value==null||value.isBlank()?"待完善":value.strip(); }
    private <T> java.util.List<T> safe(java.util.List<T> values) { return values==null?java.util.List.of():values; }
}
