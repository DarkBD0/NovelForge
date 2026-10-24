package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.ChapterBeat;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only enforces literal, unambiguous "do not mention X yet" plan boundaries. */
@Component
public class ExplicitRevealBoundaryPolicy {
    private static final Pattern FORBIDDEN_AFTER=Pattern.compile(
            "(?:不得|不能|不可)(?:提前)?(?:出现|提及)([\\p{IsHan}]{2,8})(?=[，、；。]|$)");
    private static final Pattern FORBIDDEN_BEFORE=Pattern.compile(
            "(?:^|[，；。])\\s*([\\p{IsHan}]{2,8}?)(?:仍|还)?不可出现(?=[，、；。]|$)");

    public Review apply(Novel novel,Artifact target,Action action,ModelGateway.Generated candidate,Review review) {
        if(review==null || candidate==null || candidate.content()==null || novel==null
                || action!=Action.CHAPTER && (target==null || target.kind!=Kind.CHAPTER)) return review;
        int chapter=action==Action.CHAPTER && target==null
                ? novel.artifacts.stream().filter(a->a.kind==Kind.CHAPTER && a.clean())
                    .mapToInt(a->a.chapterNumber).max().orElse(0)+1
                : target.chapterNumber;
        ChapterBeat beat=novel.artifacts.stream().filter(a->a.kind==Kind.PLAN && a.clean()
                        && a.approved()!=null && a.approved().plan!=null
                        && a.approved().plan.startChapter<=chapter && a.approved().plan.endChapter>=chapter)
                .flatMap(a->a.approved().plan.chapters.stream())
                .filter(item->item.number()==chapter).findFirst().orElse(null);
        if(beat==null || beat.revealBoundary()==null || beat.revealBoundary().isBlank()) return review;
        Set<String> forbidden=new LinkedHashSet<>();
        collect(FORBIDDEN_AFTER,beat.revealBoundary(),forbidden);
        collect(FORBIDDEN_BEFORE,beat.revealBoundary(),forbidden);
        Review result=review;
        for(String subject:forbidden) {
            int index=candidate.content().indexOf(subject);
            if(index<0) continue;
            String excerpt=sentenceAround(candidate.content(),index);
            result=result.withIssue(new ReviewIssue("第"+chapter+"章正文 > 提前揭示",
                    "本章规划明确要求暂不提及“"+subject+"”，正文却已经出现",
                    "本章揭示边界：“"+beat.revealBoundary()+"”｜候选原文：“"+excerpt+"”",
                    "删除或延后这处揭示；若确需提前改变剧情，请先由作者修订并确认章节规划的揭示边界",
                    "必须修正"));
        }
        return result;
    }

    private void collect(Pattern pattern,String boundary,Set<String> subjects) {
        Matcher matcher=pattern.matcher(boundary);
        while(matcher.find()) subjects.add(matcher.group(1));
    }

    private String sentenceAround(String content,int index) {
        int start=index;
        while(start>0 && "。！？!?\r\n".indexOf(content.charAt(start-1))<0 && index-start<50) start--;
        int end=index;
        while(end<content.length() && "。！？!?\r\n".indexOf(content.charAt(end))<0 && end-index<50) end++;
        if(end<content.length() && "。！？!?".indexOf(content.charAt(end))>=0) end++;
        return content.substring(start,end).strip();
    }
}
