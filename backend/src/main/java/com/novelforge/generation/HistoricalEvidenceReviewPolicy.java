package com.novelforge.generation;

import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Prevents model-derived historical summaries from becoming evidence by themselves.
 * A finding survives only when it quotes text that the local extractor already verified
 * against a confirmed chapter version.
 */
@Component
public class HistoricalEvidenceReviewPolicy {
    private static final Pattern CONFIRMED_QUOTE=Pattern.compile(
            "已确认依据\\s*[：:]\\s*[“\"]([^”\"]+)[”\"]");

    public Review retainGrounded(Review review,HistoricalStructuredMemoryShadowService.Aggregate aggregate) {
        if(review==null) return null;
        LinkedHashSet<String> quotes=quotes(aggregate);
        List<ReviewIssue> kept=review.issueDetails().stream().filter(issue->grounded(issue,quotes)).toList();
        return new Review(kept.stream().noneMatch(issue->"必须修正".equals(issue.severity())),
                kept.stream().map(ReviewIssue::text).toList(),review.mainlineResolved(),review.endingClear(),
                review.foreshadowingResolved(),kept);
    }

    boolean grounded(ReviewIssue issue,LinkedHashSet<String> quotes) {
        if(issue==null) return false;
        var matcher=CONFIRMED_QUOTE.matcher(issue.evidence()==null?"":issue.evidence());
        if(!matcher.find()) return false;
        String cited=matcher.group(1).trim();
        if(cited.length()<4) return false;
        return quotes.stream().anyMatch(source->source.contains(cited)||cited.contains(source));
    }

    private LinkedHashSet<String> quotes(HistoricalStructuredMemoryShadowService.Aggregate aggregate) {
        LinkedHashSet<String> result=new LinkedHashSet<>();
        if(aggregate==null) return result;
        aggregate.facts().forEach(item->item.evidence().forEach(evidence->result.add(evidence.quote())));
        aggregate.entities().forEach(item->item.evidence().forEach(evidence->result.add(evidence.quote())));
        aggregate.relations().forEach(item->item.evidence().forEach(evidence->result.add(evidence.quote())));
        return result;
    }
}
