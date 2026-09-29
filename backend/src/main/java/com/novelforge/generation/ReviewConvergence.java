package com.novelforge.generation;

import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import com.novelforge.novel.Novel.Version;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Gives findings stable identities and stops guided repair from looping forever. */
@Component
public class ReviewConvergence {
    public Review apply(Artifact target, Review current, int guidedFixRound) {
        if (current==null || current.issueDetails()==null) return current;
        Review previous=previousReview(target);
        List<ReviewIssue> previousDetails=previous==null||previous.issueDetails()==null
                ? List.of() : previous.issueDetails();
        List<ReviewIssue> identified=new ArrayList<>();
        boolean downgraded=false;
        for (ReviewIssue issue:current.issueDetails()) {
            ReviewIssue matched=bestMatch(issue,previousDetails);
            String id=matched==null ? stableId(issue) : idOf(matched);
            boolean repeatedBlocking=matched!=null && "必须修正".equals(issue.severity())
                    && "必须修正".equals(matched.severity());
            if (guidedFixRound>=2 && repeatedBlocking) {
                downgraded=true;
                identified.add(new ReviewIssue(id,issue.location(),issue.problem(),issue.evidence(),
                        authorDecisionSuggestion(issue.suggestion()),"作者决定"));
            } else {
                identified.add(new ReviewIssue(id,issue.location(),issue.problem(),issue.evidence(),
                        issue.suggestion(),issue.severity()));
            }
        }
        boolean passed=downgraded
                ? identified.stream().noneMatch(issue->"必须修正".equals(issue.severity()))
                : current.passed();
        List<String> texts=identified.stream().map(ReviewIssue::text).toList();
        return new Review(passed,texts,current.mainlineResolved(),current.endingClear(),
                current.foreshadowingResolved(),identified);
    }

    /** Backward-compatible entry point for older callers: identify only, never downgrade. */
    public Review markRepeated(Artifact target,Review current) { return apply(target,current,0); }

    /**
     * Shared deterministic identity rule for reports produced by different reviewers.
     * It intentionally uses location, problem and evidence together so differently worded
     * descriptions of one contradiction can be compared without merging unrelated findings.
     */
    public boolean sameIssue(ReviewIssue left,ReviewIssue right) {
        return left!=null && right!=null && matchScore(left,right)>=1.0;
    }

    /**
     * A comparison-only matcher for reports written independently by two reviewers.
     * This is deliberately more tolerant than {@link #sameIssue(ReviewIssue, ReviewIssue)}
     * and must never be used to authorize or suppress an automatic revision.
     */
    public boolean sameIssueForComparison(ReviewIssue left,ReviewIssue right) {
        if (left==null || right==null) return false;
        if (sameIssue(left,right)) return true;
        double location=similarity(left.location(),right.location());
        double problem=similarity(left.problem(),right.problem());
        double evidence=similarity(left.evidence(),right.evidence());
        double combined=similarity(left.problem()+left.evidence(),right.problem()+right.evidence());
        boolean usefulLocation=Math.min(normalize(left.location()).length(),normalize(right.location()).length())>=6;
        return usefulLocation && location>=.20 && (problem>=.12 || evidence>=.16 || combined>=.18);
    }

    private String authorDecisionSuggestion(String suggestion) {
        String suffix="同一问题经过两轮自动修订后仍被报告，系统已停止自动循环；请作者阅读当前版本后决定确认或手动修改。";
        return suggestion.contains("两轮自动修订")?suggestion:suggestion+" "+suffix;
    }

    private ReviewIssue bestMatch(ReviewIssue current,List<ReviewIssue> previous) {
        ReviewIssue best=null; double bestScore=0;
        for (ReviewIssue candidate:previous) {
            double score=matchScore(current,candidate);
            if (score>bestScore) { bestScore=score; best=candidate; }
        }
        return bestScore>=1.0?best:null;
    }

    private double matchScore(ReviewIssue left,ReviewIssue right) {
        if (left.issueId()!=null && left.issueId().equals(right.issueId())) return 10;
        String leftProblem=normalize(left.problem()), rightProblem=normalize(right.problem());
        if (!leftProblem.isEmpty() && leftProblem.equals(rightProblem)) return 5;
        double location=similarity(left.location(),right.location());
        double problem=similarity(left.problem(),right.problem());
        double evidence=similarity(left.evidence(),right.evidence());
        boolean usefulLocation=Math.min(normalize(left.location()).length(),normalize(right.location()).length())>=6;
        if (usefulLocation && location>=.55 && (problem>=.38 || evidence>=.42)) return 2+location+problem+evidence;
        if (problem>=.68 && evidence>=.30) return 1+problem+evidence;
        return 0;
    }

    private Review previousReview(Artifact target) {
        if (target==null) return null;
        Version latest=target.latest();
        if (latest==null) return null;
        if (latest.review!=null) return latest.review;
        if (latest.baseVersionId==null) return null;
        return target.versions.stream().filter(v->latest.baseVersionId.equals(v.id)).map(v->v.review)
                .filter(review->review!=null).findFirst().orElse(null);
    }

    private String idOf(ReviewIssue issue) { return issue.issueId()==null?stableId(issue):issue.issueId(); }

    private String stableId(ReviewIssue issue) {
        String source=normalize(issue.location())+"|"+normalize(issue.problem())+"|"+normalize(issue.evidence());
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private double similarity(String left,String right) {
        String a=normalize(left), b=normalize(right);
        if (a.isEmpty()||b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        Set<String> aa=bigrams(a), bb=bigrams(b);
        int intersection=0;
        for (String item:aa) if (bb.contains(item)) intersection++;
        return aa.isEmpty()||bb.isEmpty()?0:(2.0*intersection)/(aa.size()+bb.size());
    }

    private Set<String> bigrams(String value) {
        Set<String> result=new HashSet<>();
        if (value.length()==1) { result.add(value); return result; }
        for (int i=0;i<value.length()-1;i++) result.add(value.substring(i,i+2));
        return result;
    }

    private String normalize(String value) {
        return value==null?"":value.replaceAll("[\\s，。；：、,.!！?？‘’“”\"'（）()《》<>]+","")
                .replace("当前候选","").replace("当前版本","").replace("候选原文","")
                .toLowerCase(Locale.ROOT);
    }
}
