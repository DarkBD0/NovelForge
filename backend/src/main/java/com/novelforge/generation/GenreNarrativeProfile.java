package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Version;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves a small, explicit genre contract shared by planning, writing and review. */
@Component
public class GenreNarrativeProfile {
    public static final String VERSION="genre-narrative-v3";
    public enum Mode { SHUANG, SUSPENSE, GENERAL }
    public record Profile(Mode mode,String label,String chapterStyle,List<String> planningRules) {
        public boolean shuang() { return mode==Mode.SHUANG; }
        public Map<String,Object> asContext() {
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("policyVersion",VERSION); result.put("mode",mode.name()); result.put("label",label);
            result.put("chapterStyle",chapterStyle); result.put("planningRules",planningRules);
            return result;
        }
    }

    private final RequirementAuthority requirements;
    public GenreNarrativeProfile(RequirementAuthority requirements) { this.requirements=requirements; }

    public Profile resolve(Novel novel) {
        String evidence=evidence(novel);
        // An explicit "爽文" instruction wins over incidental mystery words in a premise.
        if(evidence.contains("爽文")) return new Profile(Mode.SHUANG,"爽文",
                "爽文推进型：按本章规划写出真实阻力、主动选择和局面变化；允许先受挫再兑现，但不能反复核实同一前提来拖延行动；兑现时写清主角所得、他人反应及下一步升级。篇幅优先给改变局面的交锋与后果，表格、盖章和流程只写对选择真正有用的部分",
                List.of("开篇可先憋屈、受挫或形成期待，也可直接兑现；具体节奏由故事密度决定，但铺垫须服务后续选择，并在开篇阶段得到可感知回报",
                        "同一前提只需一次有效确认，不得连续多章反复核实来推迟行动或回报",
                        "按本章作用分配蓄势、交锋和兑现，不要求每章同时有阻力、反转与回报；关键兑现不能被手续细节淹没",
                        "章末期待应指向下一次收益、竞争或主动选择，不能只留下继续核实真相"));
        if(evidence.contains("悬疑") || evidence.contains("推理")) return new Profile(Mode.SUSPENSE,"悬疑",
                "悬疑推进型：人物带着迫切目标行动，线索的来源、局限和代价要可信；一个真正改变判断的发现胜过连续罗列锁孔、灰尘、档案等技术细节。调查可以暂时受阻，但场景须改变人物选择、关系或风险；避免邻居或证人恰好完整讲出所需真相",
                List.of("不要求每次查看都得到新证据；安静的观察可建立气氛或改变读者理解，但不能换地点重复证明同一结论",
                        "重要线索交代观察来源和推理边界，不把猜测写成已经证实",
                        "按已确认章节之间的时间与揭示顺序承接，不提前消耗后续章节的主要发现"));
        return new Profile(Mode.GENERAL,"通用",
                "按题材和本章作用安排情节、关系、情绪或氛围；安静场景可以成立，避免同一信息无意反复及无意义凑篇幅",
                List.of("先判断本章承担铺垫、关系、氛围、揭示或兑现中的哪种作用，不统一要求行动、阻力和反转",
                        "细节可服务人物、情绪、世界质感或因果，重复意象与刻意回环不能仅凭形式相似删去",
                        "章末按自然情绪或局面收束，不强制悬念钩子"));
    }

    private String evidence(Novel novel) {
        StringBuilder text=new StringBuilder().append(value(novel.title)).append('\n')
                .append(value(novel.synopsis)).append('\n').append(requirements.resolve(novel).text());
        Version outline=approvedOutline(novel);
        if(outline!=null && outline.outlineSpec!=null) {
            text.append('\n').append(value(outline.outlineSpec.genreTone));
            text.append('\n').append(value(outline.outlineSpec.readerAttraction));
            text.append('\n').append(value(outline.outlineSpec.storyCore));
        }
        return text.toString();
    }

    private Version approvedOutline(Novel novel) {
        for(int i=novel.artifacts.size()-1;i>=0;i--) {
            Artifact artifact=novel.artifacts.get(i);
            if(artifact.kind==Kind.OUTLINE && artifact.approvedVersionId!=null && artifact.approved()!=null)
                return artifact.approved();
        }
        return null;
    }
    private String value(String value) { return value==null?"":value; }
}
