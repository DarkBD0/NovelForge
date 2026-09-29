package com.novelforge.generation;

import com.novelforge.novel.Novel.*;
import com.novelforge.novel.WordCounter;
import com.novelforge.outline.OutlineSpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/** Deterministic fixture, intentionally not a substitute for a real writing model. */
@Component
@ConditionalOnProperty(name="novelforge.model.mode", havingValue="demo", matchIfMissing=true)
public class DemoModelGateway implements ModelGateway {
    private final WordCounter counter;
    public DemoModelGateway(WordCounter counter) { this.counter = counter; }
    public String mode() { return "demo"; }
    public boolean ready() { return true; }
    public Generated generate(Request r) {
        Kind kind = r.target() == null ? Kind.valueOf(r.action().name()) : r.target().kind;
        if (r.target() != null) {
            var v = r.target().latest();
            if (r.target().kind==Kind.OUTLINE) {
                OutlineSpec spec=demoOutline(r);
                spec.storyCore=spec.storyCore+"；本次修订要求："+r.instructions();
                return new Generated(v.title,"作者可读大纲由系统根据 outlineSpec 生成",v.summary,
                        List.of(),null,spec,List.of());
            }
            return new Generated(v.title, v.content + "\n\n【离线演示修订示意】" + r.instructions(), v.summary, v.facts, v.plan);
        }
        return switch (kind) {
            case OUTLINE -> new Generated(r.novel().title + " · 大纲","作者可读大纲由系统根据 outlineSpec 生成",
                    "寻找父亲、调查灯塔、公开证据后重逢。",List.of(),null,demoOutline(r),List.of());
            case CHARACTERS -> new Generated("人物与世界设定", "【离线演示模板】\n林舟：修船师，谨慎但重承诺，目标是找到父亲。\n林远：前灯塔守卫，为保护证人藏身港口。\n许岚：港口档案员，帮助核对旧航线。\n世界：近代海港，无超自然能力。", "林舟与许岚调查灯塔，林远暗中保护证人。", List.of(new Fact("林舟", "CHARACTER", "修船师，刚回到港口", "ACTIVE"), new Fact("海港", "WORLD", "近代海港，无超自然能力", "ACTIVE")), null);
            case PLAN -> plan(r);
            case CHAPTER -> chapter(r);
        };
    }
    private OutlineSpec demoOutline(Request r) {
        OutlineSpec spec=new OutlineSpec();
        spec.storyCore="林舟回到海港寻找失踪父亲，并查清灯塔停摆与秘密航线的关系";
        spec.genreTone="近代海港悬疑，以调查行动和父子关系为主";
        spec.protagonistGoal="找到父亲并公开能够证明秘密航线的证据";
        spec.coreConflict="林舟必须在风暴封港前取得证据，同时避免证人与父亲再次暴露";
        spec.endingContract="灯塔恢复，证据公开，秘密航线被终止，林舟与父亲重逢";
        OutlineSpec.WorldRule rule=new OutlineSpec.WorldRule(); rule.id="world_harbor";
        rule.rule="港口行动受港务制度、潮汐和航路条件约束"; rule.scope="调查、出海和灯塔维护";
        rule.limit="没有许可不能进入封闭航道，风暴期间船只无法正常出港"; rule.cost="违规会失去调查资格并危及证人";
        rule.exceptionRule="无"; rule.constraintLevel="HARD"; rule.authorityStatus="CANDIDATE"; spec.worldRules.add(rule);
        OutlineSpec.CharacterArc person=new OutlineSpec.CharacterArc(); person.id="character_linz"; person.name="林舟";
        person.goal="找到父亲并查明灯塔异常"; person.motivation="弥补多年误解并阻止更多船只失踪"; person.flaw="过度谨慎，害怕再次作出错误判断";
        person.storyFunction="调查主线的行动者"; person.keyChoice="在风暴中公开证据而不是独自追踪父亲";
        person.arcDirection="从只求私人答案转向承担公共责任"; person.finalState="与父亲和解并留下维护灯塔"; spec.characters.add(person);
        OutlineSpec.StoryPart part=new OutlineSpec.StoryPart(); part.id="part_main"; part.title="失灯与归航";
        part.goal="查明灯塔停摆真相并救下返航船只"; part.conflict="走私者封锁证据，风暴压缩调查时间";
        part.turn="林舟确认父亲并非逃亡者，而是保护证人"; part.estimatedWords=(int)r.novel().targetWords;
        OutlineSpec.StoryStage stage=new OutlineSpec.StoryStage(); stage.id="stage_main"; stage.title="调查、风暴与归航";
        stage.goal="从来信追查到维护间并公开证据"; stage.estimatedWords=(int)r.novel().targetWords;
        OutlineSpec.KeyEvent event=new OutlineSpec.KeyEvent(); event.id="event_truth"; event.title="灯塔真相";
        event.event="林舟用铜钥匙进入维护间，取得航行记录并恢复灯塔"; event.consequence="返航船只避开暗礁，走私证据得以公开";
        event.characterChange="林舟选择承担港口安全责任"; event.irreversibleTurn="秘密航线失去隐蔽条件";
        stage.events.add(event); part.stages.add(stage); spec.parts.add(part);
        OutlineSpec.Foreshadow clue=new OutlineSpec.Foreshadow(); clue.id="clue_key"; clue.clue="父亲寄来的铜钥匙";
        clue.setupStageId=stage.id; clue.recoveryStageId=stage.id; clue.serviceGoal="打开维护间并取得真相证据";
        spec.foreshadows.add(clue); spec.creativeAssumptions.add("灯塔停摆与秘密航线有关");
        return spec;
    }
    public Generated outlineFoundation(Request r) {
        return new Generated("人物与世界参谋材料",
                "世界边界：近代海港，调查受港务制度、风暴和航路条件限制。\n人物骨架：林舟寻找失踪父亲；许岚提供档案能力；林远掌握秘密航线证据。",
                "海港规则和三名核心人物的剧情功能。",
                List.of(new Fact("outline_world_harbor","WORLD","近代海港，行动受港务制度、天气和航路条件限制","ACTIVE"),
                        new Fact("outline_character_linz","CHARACTER","林舟的核心目标是找到失踪父亲并查清灯塔异常","ACTIVE")),null);
    }
    public Review outlineContinuityReview(Request r,Generated g) {
        if (g!=null && g.content().contains("[演示大纲连续性冲突]")) {
            var issue=new ReviewIssue("大纲中的世界规则与高潮段","高潮直接违反候选已经建立的世界硬规则",
                    "候选原文：“[演示大纲连续性冲突]”","统一世界规则与高潮解决方式","必须修正");
            return new Review(false,List.of(issue.text()),false,false,false,List.of(issue));
        }
        return new Review(true,List.of(),false,false,false,List.of());
    }
    public Review outlinePlotReview(Request r,Generated g) {
        if (g!=null && g.content().contains("[演示大纲情节冲突]")) {
            var issue=new ReviewIssue("大纲主线","关键事件之间缺少可成立的因果",
                    "候选原文：“[演示大纲情节冲突]”","调整关键事件的前置条件和后果","必须修正");
            return new Review(false,List.of(issue.text()),false,false,false,List.of(issue));
        }
        return new Review(true,List.of(),true,true,true,List.of());
    }
    private Generated plan(Request r) {
        Plan p = new Plan();
        p.startChapter = r.context().batchNumber() == 1 ? 1 : 4;
        p.endChapter = r.context().batchNumber() == 1 ? 3 : 5;
        p.prepareNextAfterChapter = p.startChapter;
        p.finalBatch = p.startChapter == 4;
        p.triggerReason = "收到铜钥匙后，开始规划风暴与灯塔真相，以便当前批次末尾铺垫";
        p.handoff = "当前批次发现维护间入口，下一批进入灯塔并公开证据";
        p.assumptions = "尚未发生的开门和风暴只是计划，若正文偏离需重新复核";
        p.chapters = new ArrayList<>();
        for (int i = p.startChapter; i <= p.endChapter; i++) p.chapters.add(new ChapterBeat(i,
                List.of("来信", "旧账", "暗门", "风暴", "归航").get(i-1),
                "调查线推进并回收铜钥匙和灯语伏笔，第五章明确结束主线",210,
                List.of("人物在具体地点采取调查行动","行动产生一项可供下一章使用的新结果"),
                "不提前公开后续章节才确认的幕后结论","以本章调查结果改变下一步行动"));
        return new Generated("第" + r.context().batchNumber() + "批 · " + p.startChapter + "—" + p.endChapter + "章", "【离线演示使用短批次，便于验证提前规划，不代表真实批次章数】\n" + p.handoff, "灯塔调查与最终归航。", List.of(), p);
    }
    private Generated chapter(Request r) {
        int i = r.context().chapterNumber();
        String[] scenes = {
            "林舟推开修船铺的门，桌上放着一封父亲的来信。信里只有一枚铜钥匙和一行灯语。他没有急着出海，而是把钥匙的纹路画在纸上，决定先找港口档案员核对。",
            "许岚把旧航路图铺在窗边。林舟发现停灯日期与失踪船只的记录一致，这不是偶然。他们逐项核对船名，留下复印件，避免让未经验证的猜测变成结论。",
            "两人沿着维护通道来到塔底。铜钥匙与暗门锁孔吻合，但门后的海水尚未退去。林舟记下水位，约定等退潮后进入。他望见风暴逼近，提前备好绳索和照明。",
            "风暴抵达港口时，林舟用铜钥匙打开维护间。父亲留下的航行记录就在箱中，灯语对应的航线也得到证实。他们固定灯架、恢复电源，让返航船只避开暗礁。",
            "清晨，许岚将相互印证的记录交给港务处。林远带着证人走出藏身的小屋，说明失踪的原因。铜钥匙和灯语的秘密终于有了答案。林舟与父亲站在重新亮起的灯塔下，看最后一艘船平安归港。"
        };
        String seed = scenes[Math.min(i-1, 4)];
        long size = Math.min(2000, Math.max(20, r.novel().targetWords * 105 / 100 / 5));
        StringBuilder body = new StringBuilder();
        while (counter.count(body.toString()) < size) body.append(seed).append('\n');
        while (counter.count(body.toString()) > size) body.deleteCharAt(body.length()-1);
        return new Generated("第" + i + "章 · " + List.of("来信", "旧账", "暗门", "风暴", "归航").get(Math.min(i-1, 4)), body.toString(), seed,
                List.of(new Fact("铜钥匙", "FORESHADOW", i < 4 ? "铜钥匙的用途尚未完全揭晓" : "钥匙打开维护间，找到证据", i < 4 ? "OPEN" : "RESOLVED"), new Fact("灯语", "FORESHADOW", i < 4 ? "父亲的灯语指向秘密航线" : "灯语已与航线记录相互印证", i < 4 ? "OPEN" : "RESOLVED")), null);
    }
    public Review review(Request r, Generated g) {
        boolean passed = g == null || !g.content().contains("[演示检查失败]");
        return new Review(passed, passed ? List.of("离线演示检查仅验证流程，不代表文学质量合格") : List.of("检测到演示失败标记"), r.action() == Action.COMPLETE, r.action() == Action.COMPLETE, r.action() == Action.COMPLETE);
    }
    public Review continuityReview(Request r, Generated g) {
        if (g != null && g.content().contains("[演示连续性冲突]")) {
            var issue=new ReviewIssue("当前章节正文","存在演示连续性冲突标记","正文包含“[演示连续性冲突]”",
                    "删除该标记并修正对应事实","必须修正");
            return new Review(false,List.of(issue.text()),false,false,false,List.of(issue));
        }
        return new Review(true,List.of(),false,false,false,List.of());
    }
    public Review plotForeshadowReview(Request r, Generated g) {
        if (g != null && g.content().contains("[演示情节伏笔冲突]")) {
            var issue=new ReviewIssue("当前候选内容","存在演示情节或伏笔冲突标记","正文包含“[演示情节伏笔冲突]”",
                    "删除标记并修正对应的主线因果或伏笔状态","必须修正");
            return new Review(false,List.of(issue.text()),false,false,false,List.of(issue));
        }
        return new Review(true,List.of(),false,false,false,List.of());
    }
    public Review styleReview(Request r, Generated g) {
        if (g != null && g.content().contains("[演示文风问题]")) {
            var issue=new ReviewIssue("当前章节正文","存在演示文风问题标记","正文包含“[演示文风问题]”",
                    "只删除该标记，不改变剧情和档案","建议优化");
            return new Review(true,List.of(issue.text()),false,false,false,List.of(issue));
        }
        return new Review(true,List.of(),false,false,false,List.of());
    }
}
