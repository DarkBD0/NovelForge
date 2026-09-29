package com.novelforge;

import com.novelforge.generation.ModelGateway;
import com.novelforge.outline.OutlineSpec;

import java.util.List;

final class OutlineTestFixtures {
    private OutlineTestFixtures() {}

    static OutlineSpec validSpec(long targetWords,String storyCore) {
        OutlineSpec spec=new OutlineSpec();
        spec.storyCore=storyCore;
        spec.genreTone="悬疑、克制、重情节";
        spec.protagonistGoal="查清真相并作出最终选择";
        spec.coreConflict="追查真相会不断增加失去重要之人的风险";
        spec.endingContract="主线真相揭晓，关键选择完成，并形成明确结局";

        OutlineSpec.RequirementTrace requirement=new OutlineSpec.RequirementTrace();
        requirement.id="req-main"; requirement.text="完成主线并形成明确结局";
        requirement.source="作者要求"; requirement.type="EXPLICIT";
        spec.requirements=List.of(requirement);

        OutlineSpec.WorldRule rule=new OutlineSpec.WorldRule();
        rule.id="rule-evidence"; rule.rule="关键判断必须由可追溯证据推动";
        rule.scope="主线调查"; rule.limit="不能凭空获得真相"; rule.cost="错误判断会失去线索";
        rule.exceptionRule="无"; rule.constraintLevel="HARD"; rule.authorityStatus="CANDIDATE";
        spec.worldRules=List.of(rule);

        OutlineSpec.CharacterArc protagonist=new OutlineSpec.CharacterArc();
        protagonist.id="char-protagonist"; protagonist.name="主角";
        protagonist.goal="找到失踪者"; protagonist.motivation="承担自己曾逃避的责任";
        protagonist.flaw="容易凭直觉过早下结论"; protagonist.storyFunction="推动调查主线";
        protagonist.keyChoice="在安全与真相之间选择继续追查";
        protagonist.arcDirection="从逃避走向承担"; protagonist.finalState="完成选择并承担结果";
        spec.characters=List.of(protagonist);

        int words=Math.toIntExact(targetWords);
        OutlineSpec.KeyEvent event=new OutlineSpec.KeyEvent();
        event.id="event-truth"; event.title="真相揭晓"; event.event="主角找齐证据并面对真相";
        event.consequence="主线得到解决"; event.characterChange="主角不再逃避";
        event.irreversibleTurn="主角公开关键证据";
        OutlineSpec.StoryStage stage=new OutlineSpec.StoryStage();
        stage.id="stage-main"; stage.title="追查与收束"; stage.goal="完成调查并收束全部关键问题";
        stage.estimatedWords=words; stage.events=List.of(event);
        OutlineSpec.StoryPart part=new OutlineSpec.StoryPart();
        part.id="part-main"; part.title="主线"; part.goal="从异常线索推进到明确结局";
        part.conflict="证据不足且风险持续增加"; part.turn="关键证据改变主角判断";
        part.estimatedWords=words; part.stages=List.of(stage);
        spec.parts=List.of(part);

        OutlineSpec.Foreshadow clue=new OutlineSpec.Foreshadow();
        clue.id="clue-main"; clue.clue="最初的异常细节最终指向真相";
        clue.setupStageId=stage.id; clue.recoveryStageId=stage.id; clue.serviceGoal="支撑主线结局";
        spec.foreshadows=List.of(clue);

        OutlineSpec.StoryThread thread=new OutlineSpec.StoryThread();
        thread.id="thread-main"; thread.title="责任与选择";
        thread.serviceGoal="让人物变化服务主线"; thread.resolutionStageId=stage.id;
        spec.threads=List.of(thread);

        OutlineSpec.Constraint constraint=new OutlineSpec.Constraint();
        constraint.id="constraint-ending"; constraint.category="HARD";
        constraint.text="故事必须形成明确结局"; constraint.sourceRequirementId=requirement.id;
        spec.constraints=List.of(constraint);
        return spec;
    }

    static ModelGateway.Generated generated(long targetWords,String storyCore,String summary) {
        return new ModelGateway.Generated("全书大纲","由系统投影",summary,List.of(),null,
                validSpec(targetWords,storyCore),List.of());
    }
}
