package com.novelforge.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative evidence and severity policy used only by non-blocking professional shadow checks. */
@Component
public class ProfessionalReviewPolicy {
    public static final String VERSION="2026-09-28-v2";

    private static final Pattern CANDIDATE_QUOTE=Pattern.compile("候选原文\\s*[：:]\\s*[“\"](.+?)[”\"]",Pattern.DOTALL);
    private static final Pattern AUTHORITY_QUOTE=Pattern.compile("已确认依据\\s*[：:]\\s*[“\"](.+?)[”\"]",Pattern.DOTALL);
    private static final Pattern JUDGMENT_CALL=Pattern.compile(
            "缺少|没有(?:写出|交代|展开|说明|一句)|未(?:写出|交代|展开|说明|明确|落实)|"
                    +"带过|弱化|不够明确|读者(?:容易|可能)|表现形式|叙事形式|细节|铺垫|落点|"
                    +"章节分工|直接宣告|口述|转述|是否需要|建议补|仍处于.{0,12}(?:提前|落定)|"
                    +"开头.{0,30}(?:后文|随后)|先写.{0,30}(?:后文|随后)|时间跳跃|经过时间推进");

    private final ObjectMapper mapper;
    private final ReviewConvergence convergence;

    public ProfessionalReviewPolicy(ObjectMapper mapper,ReviewConvergence convergence) {
        this.mapper=mapper;
        this.convergence=convergence;
    }

    public Review normalize(Review review,ModelGateway.Generated candidate,
                            ModelGateway.Request request,Review formalReview) {
        if (review==null || review.issueDetails()==null || candidate==null) return review;
        String visible=normalize(visible(candidate));
        String authority=normalize(authority(request));
        List<ReviewIssue> formalBlocking=formalReview==null||formalReview.issueDetails()==null?List.of()
                :formalReview.issueDetails().stream().filter(issue->"必须修正".equals(issue.severity())).toList();
        List<ReviewIssue> kept=new ArrayList<>();
        for (ReviewIssue issue:review.issueDetails()) {
            String candidateQuote=quote(CANDIDATE_QUOTE,issue.evidence());
            String authorityQuote=quote(AUTHORITY_QUOTE,issue.evidence());
            // A professional checker may not discuss text that is absent from the exact candidate.
            if (candidateQuote==null || !visible.contains(normalize(candidateQuote))) continue;
            boolean groundedAuthority=authorityQuote!=null && authority.contains(normalize(authorityQuote));
            if (!groundedAuthority && !"必须修正".equals(issue.severity())) continue;
            if (!"必须修正".equals(issue.severity())) { kept.add(issue); continue; }

            boolean independentlyConfirmed=formalBlocking.stream()
                    .anyMatch(formal->convergence.sameIssueForComparison(issue,formal));
            boolean literaryJudgment=JUDGMENT_CALL.matcher(issue.problem()+issue.suggestion()).find();
            if (!groundedAuthority || !independentlyConfirmed || literaryJudgment) {
                kept.add(downgrade(issue,groundedAuthority,independentlyConfirmed,literaryJudgment));
            } else kept.add(issue);
        }
        boolean passed=kept.stream().noneMatch(issue->"必须修正".equals(issue.severity()));
        return new Review(passed,kept.stream().map(ReviewIssue::text).toList(),review.mainlineResolved(),
                review.endingClear(),review.foreshadowingResolved(),kept);
    }

    private ReviewIssue downgrade(ReviewIssue issue,boolean authority,boolean confirmed,boolean judgment) {
        String reason=!authority?"已确认依据无法在本次冻结资料中逐字核对"
                :!confirmed?"正式内容检查没有独立确认这是同一项硬冲突"
                :judgment?"该意见涉及叙事取舍、章节分配或交代详略":"尚不能确定为事实互斥";
        String suffix="专业检查已将其降为作者决定："+reason+"。";
        String suggestion=issue.suggestion().contains("专业检查已将其降为作者决定")
                ?issue.suggestion():issue.suggestion()+" "+suffix;
        return new ReviewIssue(issue.issueId(),issue.location(),issue.problem(),issue.evidence(),suggestion,"作者决定");
    }

    private String quote(Pattern pattern,String evidence) {
        Matcher matcher=pattern.matcher(evidence==null?"":evidence);
        return matcher.find()?matcher.group(1):null;
    }

    private String visible(ModelGateway.Generated candidate) {
        StringBuilder text=new StringBuilder().append(candidate.title()).append('\n')
                .append(candidate.content()).append('\n').append(candidate.summary());
        candidate.facts().forEach(fact->text.append('\n').append(fact.detail()));
        if (candidate.plan()!=null) text.append('\n').append(candidate.plan());
        return text.toString();
    }

    private String authority(ModelGateway.Request request) {
        if (request==null || request.context()==null || request.context().json()==null) return "";
        try {
            JsonNode root=mapper.readTree(request.context().json());
            StringBuilder text=new StringBuilder();
            for (String field:List.of("title","synopsis","requirements","canonBeforeChapter","currentChapterPlan",
                    "chapterBrief","acceptedReferences","formalStructuredMemory","planningState","stateModel"))
                appendText(root.get(field),text);
            return text.toString();
        } catch (Exception ignored) { return ""; }
    }

    private void appendText(JsonNode node,StringBuilder text) {
        if (node==null || node.isNull()) return;
        if (node.isTextual() || node.isNumber() || node.isBoolean()) { text.append('\n').append(node.asText()); return; }
        if (node.isArray()) node.forEach(child->appendText(child,text));
        else if (node.isObject()) node.fields().forEachRemaining(entry->appendText(entry.getValue(),text));
    }

    private String normalize(String value) { return value==null?"":value.replaceAll("\\s+","").strip(); }
}
