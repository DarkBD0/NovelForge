package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import com.novelforge.outline.OutlineSpec;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Checks that a payoff-driven outline gives its opening and stages executable outcomes. */
@Component
public class GenreOutlinePolicy {
    private final GenreNarrativeProfile profiles;

    public GenreOutlinePolicy(GenreNarrativeProfile profiles) { this.profiles=profiles; }

    public Review apply(Novel novel, Artifact target, Action action, String instructions,
                        ModelGateway.Generated candidate, Review review) {
        boolean outline=action==Action.OUTLINE || target!=null && target.kind==Kind.OUTLINE;
        OutlineSpec spec=candidate==null?null:candidate.outlineSpec();
        if(!outline || spec==null || !shuang(novel,instructions,spec)) return review;

        List<OutlineSpec.StoryStage> stages=safe(spec.parts).stream()
                .flatMap(part->safe(part.stages).stream()).toList();
        if(stages.isEmpty()) return review; // The structural validator reports missing stages.
        OutlineSpec.StoryStage opening=stages.getFirst();
        OutlineSpec.KeyEvent payoffEvent=safe(opening.events).stream()
                .filter(event->Objects.equals(event.id,spec.openingPayoffEventId))
                .findFirst().orElse(null);

        if(blank(spec.openingPayoff) || payoffEvent==null) {
            String evidence="开篇阶段“"+value(opening.title)+"”的关键事件为："
                    +safe(opening.events).stream().map(event->value(event.title)).toList()
                    +"；当前未能将首次兑现对应到其中的事件";
            review=review.withIssue(new ReviewIssue("大纲 > 开篇首次兑现",
                    "爽文开篇没有把第一次可见收益落实到开篇阶段的某个关键事件",
                    evidence,
                    "允许先写憋屈、受挫或期待，但要在开篇阶段安排有因果支撑的实际兑现，并指向发生兑现的事件", "必须修正"));
        }
        for(OutlineSpec.StoryStage stage:stages) {
            if(!blank(stage.readerPayoff) && !blank(stage.payoffResult) && !blank(stage.escalation)) continue;
            review=review.withIssue(new ReviewIssue("大纲 > "+value(stage.title),
                    "这个阶段没有交代读者期待、实际兑现结果和下一步升级",
                    "阶段目标：“"+value(stage.goal)+"”；实际兑现：“"+value(stage.payoffResult)
                            +"”；升级：“"+value(stage.escalation)+"”",
                    "用本阶段已列出的事件说明主角得到什么，并写清下一阶段的收益或局面如何升级",
                    "必须修正"));
        }
        return review;
    }

    private boolean shuang(Novel novel,String instructions,OutlineSpec spec) {
        String current=instructions==null?"":instructions;
        if(current.matches("(?s).*(?:改为|改成|调整为)(?:.{0,12})(?:悬疑|推理|严肃现实|悲剧).*")
                || current.matches("(?s).*(?:不要|不再是|改掉)(?:.{0,6})爽文.*")) return false;
        return current.contains("爽文") || profiles.resolve(novel).shuang() || value(spec.genreTone).contains("爽文");
    }

    private boolean blank(String value) { return value==null || value.isBlank(); }
    private String value(String value) { return blank(value)?"未填写":value.strip(); }
    private <T> List<T> safe(List<T> values) { return values==null?List.of():values; }
}
