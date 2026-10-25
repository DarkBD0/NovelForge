package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.outline.OutlineSpec;

import java.util.List;
import java.util.Objects;

/** Reads ending obligations only from the latest approved structured outline. */
final class EndingGoalContract {
    record Goal(String id,String goal,String proofStandard,String sourceVersionId) {}

    private EndingGoalContract() {}

    static List<Goal> from(Novel novel) {
        Artifact outline=novel.artifacts.stream().filter(a->a.kind==Kind.OUTLINE && !a.needsRevision && a.approved()!=null
                        && a.approved().outlineSpec!=null)
                .reduce((earlier,later)->later).orElse(null);
        if(outline==null) return List.of();
        OutlineSpec spec=outline.approved().outlineSpec;
        String source=outline.approvedVersionId;
        if(spec.endingGoals!=null && !spec.endingGoals.isEmpty()) {
            List<Goal> explicit=spec.endingGoals.stream().filter(Objects::nonNull)
                    .filter(g->notBlank(g.id)&&notBlank(g.goal)&&notBlank(g.proofStandard))
                    .map(g->new Goal(g.id,g.goal,g.proofStandard,source)).toList();
            if(!explicit.isEmpty()) return explicit;
        }
        if(!notBlank(spec.endingContract)) return List.of();
        return List.of(new Goal("ending_main",spec.endingContract,
                "已确认正文必须通过人物行动、可信信息或可观察的结果落实这一结局；传闻、推测和规划文字不是已发生的证据。若目标要求具体信息，模糊范围不能替代具体结果。",source));
    }

    private static boolean notBlank(String text) { return text!=null&&!text.isBlank(); }
}
