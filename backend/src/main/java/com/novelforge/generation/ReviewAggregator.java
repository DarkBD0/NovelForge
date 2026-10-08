package com.novelforge.generation;

import com.novelforge.novel.ExplicitWordLimit;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import com.novelforge.novel.Novel.Version;
import com.novelforge.novel.WordCounter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.novelforge.shared.Problem.require;

/** Applies deterministic review rules after a model report and before persistence. */
@Component
public class ReviewAggregator {
    private final WordCounter words;
    private final ExplicitWordLimit explicitWordLimit;
    private final OutlineBudgetAudit outlineBudget;
    private final FactAtomicityPolicy factAtomicity;
    private final ProseBoundaryPolicy proseBoundary;
    private final RollingPlanStatePolicy rollingPlanState;
    private final ReviewConvergence convergence;
    private final ReviewPolicy reviewPolicy;

    public ReviewAggregator(WordCounter words, ExplicitWordLimit explicitWordLimit,
                            OutlineBudgetAudit outlineBudget, FactAtomicityPolicy factAtomicity,
                            ProseBoundaryPolicy proseBoundary,
                            RollingPlanStatePolicy rollingPlanState,
                            ReviewConvergence convergence,
                            ReviewPolicy reviewPolicy) {
        this.words=words; this.explicitWordLimit=explicitWordLimit; this.outlineBudget=outlineBudget;
        this.factAtomicity=factAtomicity; this.proseBoundary=proseBoundary;
        this.rollingPlanState=rollingPlanState; this.convergence=convergence; this.reviewPolicy=reviewPolicy;
    }

    public Review aggregate(Novel novel, Artifact target, Action action, String instructions,
                            ModelGateway.Generated candidate, Version previous, Review modelReview) {
        return aggregate(novel,target,action,instructions,candidate,previous,modelReview,0);
    }

    public Review aggregate(Novel novel, Artifact target, Action action, String instructions,
                            ModelGateway.Generated candidate, Version previous, Review modelReview,
                            int guidedFixRound) {
        Review review=validate(modelReview);
        if (candidate!=null && isChapter(target,action)) {
            var limit=explicitWordLimit.from(instructions);
            if (limit.isPresent() && words.count(candidate.content())>limit.getAsLong()) {
                review=review.withIssue(new ReviewIssue("当前章节 > 字数要求","正文超过本次明确指定的单章上限",
                        "当前正文按项目口径为 "+words.count(candidate.content())+" 字，上限为 "+limit.getAsLong()+" 字",
                        "压缩正文至上限以内，同时保留章节规划中的主事件和结果","必须修正"));
            }
        }
        review=outlineBudget.apply(novel,target,action,candidate,review);
        review=ignoreEmptyFactFindingForPlan(target,action,candidate,review);
        // Chapter facts are internal state, not novel prose. They are normalized by
        // StateExtractionPolicy after the prose review succeeds, so a formatting
        // defect in generated state must never send otherwise unchanged prose back
        // through the content-revision loop.
        review=proseBoundary.apply(target,action,candidate,review);
        review=rollingPlanState.apply(novel,target,action,instructions,candidate,review);
        review=reviewPolicy.normalize(validate(review),candidate,previous);
        review=convergence.apply(target,validate(review),guidedFixRound);
        return deduplicate(validate(review));
    }

    /** Style reports are advisory and already severity-normalized by StyleReviewPolicy. */
    public Review aggregateAdvisory(Review review) {
        return deduplicate(validate(review));
    }

    /** Shadow reports are recorded for evaluation and never participate in confirmation gating. */
    public Review aggregateShadow(Review review) {
        return deduplicate(validate(review));
    }

    /** Shadow findings use the same current-candidate evidence rule, but remain non-blocking experiments. */
    public Review aggregateShadow(Review review,ModelGateway.Generated candidate) {
        return deduplicate(validate(reviewPolicy.normalize(validate(review),candidate,null)));
    }

    private Review validate(Review review) {
        require(review!=null && review.issues()!=null && review.issueDetails()!=null,
                "模型检查报告缺少必要字段");
        require(review.issueDetails().stream().noneMatch(java.util.Objects::isNull),
                "模型检查报告包含空问题项");
        return review;
    }

    private boolean isChapter(Artifact target,Action action) {
        return action==Action.CHAPTER || (target!=null && target.kind==Kind.CHAPTER);
    }

    /** Plans describe future work. Their contract requires facts=[], so a model must not block them for that. */
    private Review ignoreEmptyFactFindingForPlan(Artifact target,Action action,
                                                  ModelGateway.Generated candidate,Review review) {
        boolean plan=action==Action.PLAN || (target!=null && target.kind==Kind.PLAN);
        if (!plan || candidate==null || candidate.facts()==null || !candidate.facts().isEmpty()) return review;
        List<ReviewIssue> kept=review.issueDetails().stream().filter(issue->{
            String text=issue.location()+issue.problem()+issue.evidence()+issue.suggestion();
            return !(text.contains("档案增量") && (text.contains("缺失") || text.contains("补充") || text.contains("为空")));
        }).toList();
        if (kept.size()==review.issueDetails().size()) return review;
        boolean passed=kept.stream().noneMatch(issue->"必须修正".equals(issue.severity()));
        return new Review(passed,kept.stream().map(ReviewIssue::text).toList(),review.mainlineResolved(),
                review.endingClear(),review.foreshadowingResolved(),kept);
    }

    private Review deduplicate(Review review) {
        Map<String,ReviewIssue> unique=new LinkedHashMap<>();
        for (ReviewIssue issue:review.issueDetails()) {
            String key=key(issue);
            ReviewIssue existing=unique.get(key);
            if (existing==null || severityRank(issue.severity())>severityRank(existing.severity())) unique.put(key,issue);
        }
        List<ReviewIssue> details=new ArrayList<>(unique.values());
        List<String> issues=details.stream().map(ReviewIssue::text).toList();
        if (details.equals(review.issueDetails()) && issues.equals(review.issues())) return review;
        return new Review(review.passed(),issues,review.mainlineResolved(),review.endingClear(),
                review.foreshadowingResolved(),details);
    }

    private String key(ReviewIssue issue) {
        if (issue.issueId()!=null && !issue.issueId().isBlank()) return issue.issueId();
        return normalize(issue.location())+"|"+normalize(issue.problem())+"|"+normalize(issue.evidence());
    }

    private String normalize(String value) {
        return value==null?"":value.replaceAll("[\\s，。；：、,.!！?？‘’“”\"']+","").toLowerCase(Locale.ROOT);
    }

    private int severityRank(String severity) {
        if ("必须修正".equals(severity)) return 3;
        if ("作者决定".equals(severity)) return 2;
        return 1;
    }
}
