package com.novelforge.generation;

import com.novelforge.novel.Novel.Fact;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Keeps one persisted fact focused on one subject and one action or state. */
@Component
public class FactAtomicityPolicy {
    private static final Pattern SENTENCE_BOUNDARY=Pattern.compile("[；;。]+");
    private static final Pattern ACTION_JOINER=Pattern.compile("(?:，|,)?(?:并且|并|随后|后来|同时|继而|之后|然后|又)(?=[^，,；;。]{2,})");

    public Review apply(ModelGateway.Generated candidate, Review review) {
        if (candidate==null || candidate.facts()==null) return review;
        Review result=review;
        for (Fact fact:candidate.facts()) {
            List<String> claims=claims(fact.detail());
            if (claims.size()<2) continue;
            String preview=claims.stream().limit(3).map(value->"“"+value+"”").reduce((a,b)->a+"、"+b).orElse("");
            result=result.withIssue(new ReviewIssue(
                    "当前内容 > 档案增量中以“"+shortText(fact.detail())+"”开头的条目",
                    "一条档案同时记录了多个动作或状态，后续核对时容易把部分有依据误判为整条冲突",
                    "当前档案原文：“"+fact.detail()+"”；可拆分为："+preview,
                    "只拆分当前档案条目，每条保留一个主体和一个动作或状态；不要为了证明档案向正文补写解释句",
                    "必须修正"));
        }
        return result;
    }

    /** Conservative clauses used by the evidence audit; no prose is rewritten here. */
    public List<String> claims(String detail) {
        if (detail==null || detail.isBlank()) return List.of();
        List<String> result=new ArrayList<>();
        for (String sentence:SENTENCE_BOUNDARY.split(detail)) {
            for (String clause:ACTION_JOINER.split(sentence)) {
                String value=clause.strip().replaceAll("^[，,：:\\s]+|[，,：:\\s]+$","");
                if (!value.isBlank()) result.add(value);
            }
        }
        return result.isEmpty()?List.of(detail.strip()):List.copyOf(result);
    }

    private String shortText(String value) {
        String text=value==null?"未命名内容":value.replaceAll("\\s+"," ").strip();
        return text.length()<=24?text:text.substring(0,24)+"…";
    }
}
