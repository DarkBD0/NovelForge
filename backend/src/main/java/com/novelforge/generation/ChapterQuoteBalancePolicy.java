package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import java.util.ArrayDeque;
import java.util.Deque;

/** Catches unmistakable unmatched Chinese dialogue quotes before a chapter can be approved. */
final class ChapterQuoteBalancePolicy {
    private ChapterQuoteBalancePolicy() {}

    static Review apply(Artifact target, Action action, ModelGateway.Generated candidate, Review review) {
        if (candidate==null || candidate.content()==null
                || action!=Action.CHAPTER && (target==null || target.kind!=Kind.CHAPTER)) return review;
        String content=candidate.content();
        Deque<Integer> openings=new ArrayDeque<>();
        for (int i=0;i<content.length();i++) {
            char current=content.charAt(i);
            if (current=='“') openings.push(i);
            else if (current=='”') {
                if (openings.isEmpty()) return issue(review,content,i,"多了一个右引号");
                openings.pop();
            }
        }
        if (!openings.isEmpty()) return issue(review,content,openings.getLast(),"有左引号没有对应的右引号");
        return review;
    }

    private static Review issue(Review review,String content,int position,String problem) {
        int start=content.lastIndexOf('\n',position);
        int end=content.indexOf('\n',position);
        String excerpt=content.substring(start<0?0:start+1,end<0?content.length():end).strip();
        if (excerpt.length()>160) excerpt=excerpt.substring(0,160);
        return review.withIssue(new ReviewIssue("当前章节 > 对话标点",problem,
                "所在行原文："+excerpt,"补齐或删除错误引号，只改标点，不改变人物说话内容","必须修正"));
    }
}
