package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.ChapterBeat;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Non-blocking reader-facing signals for repeated chapter purposes. */
@Component
public class PlanBeatRepetitionPolicy {
    private static final Pattern PROCEDURE=Pattern.compile("核对|核验|复核|验证|确认|登记|归档|结算|移交|交接|签收|封存|整理(?:材料|档案|记录)|查(?:档|记录|资料)");
    private static final Pattern HUMAN_CHANGE=Pattern.compile("选择|决定|拒绝|失去|牺牲|承担|告别|救出|获救|反击|背叛|坦白|争执|和解|改变关系|误判|付出代价|受伤|死亡|失踪|暴露|翻盘");
    private static final Pattern INFORMATION_STEP=Pattern.compile("调查|核对|确认|证实|比对|查找|检索|追问|询问|查看|翻查|寻找|追查|查明|发现");
    private static final Pattern CONSEQUENTIAL_CHANGE=Pattern.compile("争执|和解|决裂|信任|失去|付出代价|承担|拒绝|背叛|救出|获救|反击|暴露|关系改变|被迫选择|主动选择|公开");
    private static final Pattern NEXT_LOOKUP=Pattern.compile("(?:继续|再|下一步|决定|准备|打算|转而|先去|仍要).{0,18}(?:查|找|问|核对|比对|调查|追索|追查)");

    public Review apply(Novel novel, Artifact target, Action action, ModelGateway.Generated candidate, Review review) {
        if (candidate==null || candidate.plan()==null || review==null
                || action!=Action.PLAN && (target==null || target.kind!=Kind.PLAN)) return review;
        List<ChapterBeat> beats=candidate.plan().chapters;
        if (beats==null || beats.size()<3) return review;
        int confirmedThrough=novel.artifacts.stream().filter(a->a.kind==Kind.CHAPTER && a.clean())
                .mapToInt(a->a.chapterNumber).max().orElse(0);
        List<ChapterBeat> future=beats.stream().filter(beat->beat!=null && beat.number()>confirmedThrough)
                .sorted(Comparator.comparingInt(ChapterBeat::number)).toList();
        // A short work can spend quiet chapters on relationships or atmosphere. Flag
        // only a narrow pattern where adjacent beats both end by seeking another source.
        if(novel.targetWords>0 && novel.targetWords<=12_000 && future.size()>=2) {
            ChapterBeat first=future.get(0),second=future.get(1);
            if(second.number()==first.number()+1 && informationOnly(first) && informationOnly(second)) {
                ReviewIssue issue=new ReviewIssue("规划第"+first.number()+"—"+second.number()+"章",
                        "短篇连续两章主要在核对线索，章末又转向下一轮查证，读者可能还没经历人物处境的变化",
                        "第"+first.number()+"章：‘"+first.purpose()+"’→‘"+first.endingHook()+"’；第"
                                +second.number()+"章：‘"+second.purpose()+"’→‘"+second.endingHook()+"’",
                        "请判断两章是否已有不同的关系、情绪或局面作用；若只是让同一事实更确定，可合并部分核对，"
                                +"把篇幅留给已有角色的选择或后果。安静铺垫有效时可以保留，不必强加反转。",
                        "作者决定");
                List<ReviewIssue> details=new ArrayList<>(review.issueDetails()); details.add(issue);
                return new Review(review.passed(),details.stream().map(ReviewIssue::text).toList(),
                        review.mainlineResolved(),review.endingClear(),review.foreshadowingResolved(),details);
            }
        }
        for (int i=0;i+2<future.size();i++) {
            ChapterBeat first=future.get(i),second=future.get(i+1),third=future.get(i+2);
            if (second.number()!=first.number()+1 || third.number()!=second.number()+1
                    || !similar(first.purpose(),second.purpose()) || !similar(second.purpose(),third.purpose())) continue;
            ReviewIssue issue=new ReviewIssue("规划第"+first.number()+"—"+third.number()+"章",
                    "连续三章的剧情目的高度相似，可能只是更换人物或场地重复同一结果",
                    "第"+first.number()+"章：“"+first.purpose()+"”；第"+second.number()+"章：“"
                            +second.purpose()+"”；第"+third.number()+"章：“"+third.purpose()+"”",
                    "请先判断题材和各章作用：相似事件是否带来新的理解、情绪、关系或后果，或属于有意回环。若只是同一信息反复，可压缩、缩短或合并；不要为避免相似而硬加阻力或反转。",
                    "作者决定");
            List<ReviewIssue> details=new ArrayList<>(review.issueDetails()); details.add(issue);
            return new Review(review.passed(),details.stream().map(ReviewIssue::text).toList(),
                    review.mainlineResolved(),review.endingClear(),review.foreshadowingResolved(),details);
        }
        // Wording may differ while the reader still experiences the same kind of
        // verification scene. This is only a prompt for author judgment, never a gate.
        for (int i=0;i<future.size();i++) {
            List<ChapterBeat> window=future.subList(i,Math.min(i+5,future.size()));
            List<ChapterBeat> procedural=window.stream().filter(this::procedureDominated).limit(3).toList();
            if (procedural.size()<3 || procedural.get(2).number()-procedural.get(0).number()>4) continue;
            ReviewIssue issue=new ReviewIssue("规划第"+procedural.get(0).number()+"—"+procedural.get(2).number()+"章",
                    "多章以核验、归档或交接作为核心变化，读者可能反复经历相似的流程",
                    procedural.stream().map(beat->"第"+beat.number()+"章：“"+beat.purpose()+"”")
                            .collect(java.util.stream.Collectors.joining("；")),
                    "请从读者角度判断这些流程是否带来不同的人物选择、关系、理解或后果；若只是重复确认已知结论，可压缩或合并。流程本身有戏剧作用时可以保留，不必强加反转。",
                    "作者决定");
            List<ReviewIssue> details=new ArrayList<>(review.issueDetails()); details.add(issue);
            return new Review(review.passed(),details.stream().map(ReviewIssue::text).toList(),
                    review.mainlineResolved(),review.endingClear(),review.foreshadowingResolved(),details);
        }
        return review;
    }

    private boolean informationOnly(ChapterBeat beat) {
        String purpose=beat.purpose()==null?"":beat.purpose();
        String ending=beat.endingHook()==null?"":beat.endingHook();
        return INFORMATION_STEP.matcher(purpose).find()
                && !CONSEQUENTIAL_CHANGE.matcher(purpose+" "+ending).find()
                && NEXT_LOOKUP.matcher(ending).find();
    }

    private boolean procedureDominated(ChapterBeat beat) {
        String purpose=beat.purpose()==null?"":beat.purpose();
        return PROCEDURE.matcher(purpose).find() && !HUMAN_CHANGE.matcher(purpose).find();
    }

    private boolean similar(String left,String right) {
        String a=normalize(left),b=normalize(right);
        if (a.length()<14 || b.length()<14) return false;
        Set<String> aa=bigrams(a),bb=bigrams(b);
        if (aa.isEmpty() || bb.isEmpty()) return false;
        long overlap=aa.stream().filter(bb::contains).count();
        return 2.0*overlap/(aa.size()+bb.size())>=0.82;
    }

    private String normalize(String value) {
        return value==null?"":value.replaceAll("[0-9０-９一二三四五六七八九十百千万]+","")
                .replaceAll("[^\\p{IsHan}A-Za-z]","").toLowerCase(java.util.Locale.ROOT);
    }

    private Set<String> bigrams(String value) {
        Set<String> result=new HashSet<>();
        for (int i=0;i<value.length()-1;i++) result.add(value.substring(i,i+2));
        return result;
    }
}
