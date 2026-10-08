package com.novelforge.generation;

import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import com.novelforge.novel.Novel.Version;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Makes the structured severity, rather than a contradictory model flag, control confirmation blocking. */
@Component
public class ReviewPolicy {
    /** Persisted with every report so upgraded checking rules make older reports visibly stale. */
    public static final String VERSION="2026-10-08-v8";

    private static final Pattern QUOTED=Pattern.compile("[‘“\"']([^’”\"']{4,})[’”\"']");
    private static final Pattern CURRENT_TEXT_CLAIM=Pattern.compile("候选原文|当前(?:候选)?原文|正文原文|(当前|候选|正文|摘要|档案|标题|文中|同段|一句).{0,24}(写|说|表述|出现|包含|使用|声明)");
    private static final Pattern OMISSION=Pattern.compile("(正文|候选|当前内容|本章).{0,14}(没有|缺少|遗漏|未.{0,8}(出现|写出|落实|交代|包含|提及|完成|找到))");

    public Review normalize(Review review) {
        return normalize(review,null,null);
    }

    /** Drops a blocking finding only when it demonstrably quotes removed old text as if it were still current. */
    public Review normalize(Review review, ModelGateway.Generated candidate, Version previous) {
        if (review==null || review.issueDetails()==null) return review;
        if (review.passed() && review.issueDetails().stream().allMatch(this::legacyFinding)) return review;
        List<ReviewIssue> details=new ArrayList<>(review.issueDetails());
        if (candidate!=null) details.removeIf(issue->groundedOmissionReportedAsMissing(issue,candidate)
                || planTreatedAsExhaustiveChecklist(issue)
                || negativeBoundaryDemandedAsProse(issue)
                || ungroundedCurrentTextClaim(issue,candidate)
                || previous!=null && staleOldTextClaim(issue,candidate,previous));
        details=details.stream().map(this::keepMetadataFixOutOfProse).toList();
        boolean blocking=details.stream().anyMatch(issue->"必须修正".equals(issue.severity()));
        boolean passed=!blocking;
        List<String> issues=details.stream().map(ReviewIssue::text).toList();
        if (review.passed()==passed && details.equals(review.issueDetails()) && issues.equals(review.issues())) return review;
        return new Review(passed,issues,review.mainlineResolved(),review.endingClear(),
                review.foreshadowingResolved(),details);
    }

    private boolean staleOldTextClaim(ReviewIssue issue,ModelGateway.Generated candidate,Version previous) {
        String reportText=reportText(issue);
        if (!"必须修正".equals(issue.severity()) || !CURRENT_TEXT_CLAIM.matcher(reportText).find()
                || OMISSION.matcher(reportText).find()) return false;
        String current=visible(candidate.title(),candidate.content(),candidate.summary(),candidate.facts());
        String old=visible(previous.title,previous.content,previous.summary,previous.facts);
        var matcher=QUOTED.matcher(reportText);
        while (matcher.find()) {
            String quote=normalizeText(matcher.group(1));
            if (quote.length()>=4 && normalizeText(old).contains(quote) && !normalizeText(current).contains(quote)) return true;
        }
        return false;
    }

    /** A positive claim about current text must carry at least one literal quote found in that exact candidate. */
    private boolean ungroundedCurrentTextClaim(ReviewIssue issue,ModelGateway.Generated candidate) {
        String reportText=reportText(issue);
        if (!"必须修正".equals(issue.severity()) || !CURRENT_TEXT_CLAIM.matcher(reportText).find()
                || OMISSION.matcher(reportText).find()) return false;
        String current=normalizeText(visible(candidate.title(),candidate.content(),candidate.summary(),candidate.facts()));
        var matcher=QUOTED.matcher(reportText);
        while (matcher.find()) {
            String quote=normalizeText(matcher.group(1));
            if (quote.length()>=4 && current.contains(quote)) return false;
        }
        return true;
    }

    /** Removes the narrow, provable false-positive: the report says a quoted phrase is absent although it is literal current text. */
    private boolean groundedOmissionReportedAsMissing(ReviewIssue issue,ModelGateway.Generated candidate) {
        if(!"必须修正".equals(issue.severity())) return false;
        String report=reportText(issue);
        if(!OMISSION.matcher(report).find()) return false;
        String current=normalizeText(visible(candidate.title(),candidate.content(),candidate.summary(),List.of()));
        Pattern missingQuote=Pattern.compile("(?:没有|缺少|遗漏|未.{0,8}(?:出现|写出|落实|交代|包含|提及|完成|找到)).{0,40}[‘“\\\"']([^’”\\\"']{4,})[’”\\\"']");
        var matcher=missingQuote.matcher(report);
        while(matcher.find()) if(current.contains(normalizeText(matcher.group(1)))) return true;
        return false;
    }

    /** A chapter plan fixes required beats and boundaries; it is not an exhaustive list of every scene detail. */
    private boolean planTreatedAsExhaustiveChecklist(ReviewIssue issue) {
        if(!"必须修正".equals(issue.severity())) return false;
        String text=reportText(issue);
        boolean exhaustive=text.matches("(?s).*(?:超出.{0,18}(?:章节)?(?:规划|计划)(?:的)?范围|(?:规划|计划).{0,24}未(?:提及|安排|要求)|未在.{0,12}(?:规划|计划).{0,12}(?:提及|安排)).*");
        boolean realConflict=text.matches("(?s).*(?:冲突|矛盾|违反|提前揭示|提前推进|改变主线|遗漏必须|缺少必须).*");
        return exhaustive&&!realConflict;
    }

    /** Negative reveal boundaries constrain what may happen; prose need not restate that the forbidden event did not happen. */
    private boolean negativeBoundaryDemandedAsProse(ReviewIssue issue) {
        if(!"必须修正".equals(issue.severity())) return false;
        String text=reportText(issue);
        boolean omission=OMISSION.matcher(text).find();
        boolean negative=text.matches("(?s).*(?:不赋予|不得|不能|不会|不构成|不要|不揭示|不说明).*");
        boolean actualViolation=text.matches("(?s).*(?:正文却|实际却|已经|发生了).{0,24}(?:违反|赋予|揭示|说明|构成).*");
        return omission&&negative&&!actualViolation;
    }

    private String reportText(ReviewIssue issue) {
        return issue.location()+"\n"+issue.problem()+"\n"+issue.evidence()+"\n"+issue.suggestion();
    }

    private boolean legacyFinding(ReviewIssue issue) {
        return "旧版检查报告未单独记录依据".equals(issue.evidence());
    }

    private ReviewIssue keepMetadataFixOutOfProse(ReviewIssue issue) {
        String scope=issue.location()+" "+issue.problem();
        String suggestion=issue.suggestion();
        boolean metadata=scope.contains("档案")||scope.contains("摘要");
        boolean alreadyProhibits=suggestion.matches(".*(?:不要|不得|禁止).{0,16}(?:向)?正文.{0,12}(?:补写|补充|添加|写明|说明|证明).*");
        boolean asksForProse=suggestion.contains("正文") && (suggestion.contains("补写")
                || suggestion.contains("补充") || suggestion.contains("添加") || suggestion.contains("写明")
                || suggestion.contains("说明") || suggestion.contains("证明"));
        if (!metadata || !asksForProse || alreadyProhibits) return issue;
        return new ReviewIssue(issue.issueId(),issue.location(),issue.problem(),issue.evidence(),
                "只修改摘要或档案：删除无依据的声明，或拆分并保留正文已经支持的事实；不要向正文补写检查说明",
                issue.severity());
    }

    private String visible(String title,String content,String summary,List<com.novelforge.novel.Novel.Fact> facts) {
        StringBuilder text=new StringBuilder().append(title).append('\n').append(content).append('\n').append(summary);
        if (facts!=null) facts.forEach(fact->text.append('\n').append(fact.detail()));
        return text.toString();
    }

    private String normalizeText(String value) {
        return value==null?"":value.replaceAll("\\s+","").strip();
    }
}
