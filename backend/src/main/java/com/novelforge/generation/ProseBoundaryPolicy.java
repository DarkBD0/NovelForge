package com.novelforge.generation;

import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prevents checker-facing notes and workflow language from becoming novel prose. */
@Component
public class ProseBoundaryPolicy {
    private static final Pattern MACHINE_EXPLANATION=Pattern.compile(
            "[^。！？!?\\r\\n]{0,80}(?:仅|只)?(?:用于|供|为了|以便)(?:后续)?(?:核对|验证|检查)[^。！？!?\\r\\n]{0,80}[。！？!?]?|"+
            "[^。！？!?\\r\\n]{0,80}(?:检查器|审稿器|一致性检查|检查报告|检查意见)(?:需要|要求|通过|核对|找到|认为)[^。！？!?\\r\\n]{0,80}[。！？!?]?");
    private static final Pattern STORY_CHAPTER_REFERENCE=Pattern.compile(
            "第[0-9０-９一二三四五六七八九十百]+章(?:里|中|时|的时候)?(?:我|你|他|她|我们|他们)(?:把|去|到|给|拿|看|说|做|发现|已经|还|就)");

    public Review apply(Artifact target, Action action, ModelGateway.Generated candidate, Review review) {
        boolean chapter=action==Action.CHAPTER || target!=null&&target.kind==Kind.CHAPTER;
        if (!chapter || candidate==null || candidate.content()==null) return review;
        Matcher matcher=MACHINE_EXPLANATION.matcher(candidate.content());
        Review result=review;
        while (matcher.find()) {
            String sentence=matcher.group().strip();
            if (sentence.isBlank()) continue;
            result=result.withIssue(new ReviewIssue("当前章节正文 > 机器核对说明",
                    "正文混入了面向检查流程的说明，不属于故事中的人物行动、对白或叙述",
                    "候选原文：“"+sentence+"”",
                    "删除这句机器说明；如果其中包含对后文有用的事实，只把事实保存到摘要或档案，不要改写成正文证明句",
                    "必须修正"));
        }
        Matcher chapterReference=STORY_CHAPTER_REFERENCE.matcher(candidate.content());
        while (chapterReference.find()) {
            int end=Math.min(candidate.content().length(),chapterReference.end()+36);
            String excerpt=candidate.content().substring(chapterReference.start(),end).split("[。！？!?\\r\\n]",2)[0];
            result=result.withIssue(new ReviewIssue("当前章节正文 > 故事内章节编号",
                    "人物经历被写成小说的章节编号，读者会看到内部写作定位",
                    "候选原文：“"+excerpt+"”",
                    "若人物在回忆故事内的事，改用自然时间或事件称呼；不要改动已发生的事件本身",
                    "必须修正"));
        }
        return result;
    }
}
