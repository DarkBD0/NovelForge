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
        return result;
    }
}
