package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import com.novelforge.novel.WordCounter;
import org.springframework.stereotype.Component;

/** Detects an impossible chapter-plan word allocation before an author approves it. */
@Component
public class PlanWordBudgetAudit {
    private final WordCounter words;

    public PlanWordBudgetAudit(WordCounter words) { this.words=words; }

    public Review apply(Novel novel,Artifact target,Action action,ModelGateway.Generated candidate,Review review) {
        if((action!=Action.PLAN && (target==null || target.kind!=Kind.PLAN))
                ||candidate==null || candidate.plan()==null || review==null) return review;
        int nextChapter=novel.artifacts.stream().filter(a->a.kind==Kind.CHAPTER && a.approved()!=null)
                .mapToInt(a->a.chapterNumber).max().orElse(0)+1;
        long planned=0;
        for(Artifact artifact:novel.artifacts) {
            if(artifact.kind!=Kind.PLAN || artifact.approved()==null
                    ||target!=null && artifact.id.equals(target.id) || artifact.approved().plan==null) continue;
            planned+=suggestedWords(artifact.approved().plan,nextChapter);
        }
        planned+=suggestedWords(candidate.plan(),nextChapter);
        long confirmed=words.approvedWords(novel);
        long reserve=candidate.plan().finalBatch?0:100;
        long projected=confirmed+planned+reserve;
        if(projected<=novel.approvedMaxWords) return review;
        return review.withIssue(new ReviewIssue("章节规划 > 全书字数预算",
                "未写章节的建议字数合计超过已批准的全书上限",
                "已确认正文 "+confirmed+" 字，未写章节建议约 "+planned+" 字"
                        +(reserve>0?"，并至少预留后续 100 字":"")
                        +"，合计 "+projected+" 字；当前上限 "+novel.approvedMaxWords+" 字",
                "合并或压缩尚未写的剧情及建议篇幅；如果确实需要更长的故事，请由作者先调整字数上限。"
                        +"建议字数不是单章确认门禁，但规划总量必须留得下结局。","必须修正"));
    }

    private long suggestedWords(Novel.Plan plan,int nextChapter) {
        if(plan.chapters==null) return 0;
        return plan.chapters.stream().filter(beat->beat!=null && beat.number()>=nextChapter
                && beat.targetWords()!=null && beat.targetWords()>0)
                .mapToLong(beat->beat.targetWords()).sum();
    }
}
