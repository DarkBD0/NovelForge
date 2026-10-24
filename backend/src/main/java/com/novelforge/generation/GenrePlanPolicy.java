package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.ChapterBeat;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Deterministic guard against turning an explicitly requested爽文 into repeated premise verification. */
@Component
public class GenrePlanPolicy {
    private static final Pattern PAYOFF=Pattern.compile("offer|录用|签约|到手|拿下|获利|收益|涨薪|待遇|奖励|胜出|翻盘|打脸|震惊|争抢|抢人|唯一候选|跳过宣讲|直接面试",Pattern.CASE_INSENSITIVE);
    private static final Pattern VERIFY=Pattern.compile("确认|核对|调查|打听|查遍|查询|踩点|验证|问清|证明");
    private static final Pattern PROGRESS=Pattern.compile("投递|争取|面试|反击|出手|行动|选择|决定|被拒|受挫|竞争|谈判|达成|改变|抢占|拿下|获利|录用|签约|机会|阻力|冲突|反转");
    private final GenreNarrativeProfile profiles;
    public GenrePlanPolicy(GenreNarrativeProfile profiles) { this.profiles=profiles; }

    public Review apply(Novel novel, Artifact target, Action action, ModelGateway.Generated candidate, Review review) {
        boolean plan=action==Action.PLAN || target!=null && target.kind==Kind.PLAN;
        if(!plan || candidate==null || candidate.plan()==null || !profiles.resolve(novel).shuang()) return review;
        Novel.Plan planValue=candidate.plan();
        int confirmedThrough=novel.artifacts.stream().filter(a->a.kind==Kind.CHAPTER && a.clean())
                .mapToInt(a->a.chapterNumber).max().orElse(0);
        int cadenceStart=action==Action.PLAN?planValue.startChapter:Math.max(planValue.startChapter,confirmedThrough+1);
        // This policy guides future writing; occurred chapters are immutable evidence, not a target for retroactive repair.
        if(action==Action.PLAN && planValue.startChapter!=1 || cadenceStart>3 && confirmedThrough==0) return review;
        List<ChapterBeat> opening=planValue.chapters.stream().filter(beat->beat.number()>=cadenceStart)
                .sorted(Comparator.comparingInt(ChapterBeat::number)).limit(3).toList();
        if(opening.isEmpty()) return review;
        boolean payoff=opening.stream().map(this::text).anyMatch(value->PAYOFF.matcher(value).find());
        boolean repeatedVerification=opening.size()>=3 && opening.stream().map(this::text)
                .allMatch(value->VERIFY.matcher(value).find() && !PROGRESS.matcher(value).find()) && !payoff;
        if(repeatedVerification) review=review.withIssue(new ReviewIssue("开篇前三章的章节规划",
                "连续三章都在核实同一个重生或秋招前提，规划节奏被写成了悬疑调查",
                "三章的核心变化均是确认、查询或验证，既没有兑现，也没有新的主动冲突",
                "保留必要的压抑或铺垫，但让后续章节推进真实阻力、主角选择与可预期的回报；不要反复证明同一前提","必须修正"));
        return review;
    }

    private String text(ChapterBeat beat) {
        return String.join(" ",beat.title(),beat.purpose(),String.join(" ",beat.sceneBeats()),beat.endingHook());
    }
}
