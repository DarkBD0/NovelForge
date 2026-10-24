package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Advisory guard for replaying a prior chapter's multi-line dialogue in a new scene. */
final class ChapterDialogueRepetitionPolicy {
    private static final Pattern QUOTE=Pattern.compile("“([^”\\r\\n]{8,100})”");
    private ChapterDialogueRepetitionPolicy() {}

    static Review apply(Novel novel,Artifact target,Action action,ModelGateway.Generated candidate,Review review) {
        if(candidate==null||candidate.content()==null) return review;
        if(action!=Action.CHAPTER && (target==null||target.kind!=Kind.CHAPTER)) return review;
        int chapter=target!=null&&target.kind==Kind.CHAPTER?target.chapterNumber
                :novel.artifacts.stream().filter(a->a.kind==Kind.CHAPTER&&a.clean())
                        .mapToInt(a->a.chapterNumber).max().orElse(0)+1;
        Set<String> current=quotes(candidate.content());
        if(current.size()<3) return review;
        List<Artifact> recent=novel.artifacts.stream()
                .filter(a->a.kind==Kind.CHAPTER&&a.clean()&&a.chapterNumber<chapter)
                .sorted(Comparator.comparingInt((Artifact a)->a.chapterNumber).reversed()).limit(3).toList();
        for(Artifact prior:recent) {
            Set<String> old=quotes(prior.approved().content);
            List<String> repeated=current.stream().filter(old::contains).limit(4).toList();
            if(repeated.size()<3) continue;
            String sample=repeated.stream().limit(3).map(value->"“"+value+"”")
                    .reduce((left,right)->left+"、"+right).orElse("");
            ReviewIssue issue=new ReviewIssue("RHYTHM:REPEATED_DIALOGUE:"+prior.chapterNumber,
                    "当前章节的对话，与已确认第"+prior.chapterNumber+"章相比",
                    "连续多句问答沿用前章原话，容易让新场景像上一场的重播",
                    "两章均逐字出现："+sample,
                    "保留本章真正不同的阻力和选择；重复的问答可合并或改成新场景特有的问题，若是有意回声则由作者保留",
                    "作者决定");
            List<ReviewIssue> details=new ArrayList<>(review.issueDetails()); details.add(issue);
            List<String> texts=new ArrayList<>(review.issues()); texts.add(issue.text());
            review=new Review(review.passed(),texts,review.mainlineResolved(),review.endingClear(),
                    review.foreshadowingResolved(),details);
        }
        return review;
    }

    private static Set<String> quotes(String content) {
        Set<String> quotes=new LinkedHashSet<>();
        Matcher matcher=QUOTE.matcher(content==null?"":content);
        while(matcher.find()) quotes.add(matcher.group(1).strip());
        return quotes;
    }
}
