package com.novelforge.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import com.novelforge.novel.Novel.ChapterBeat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A focused second opinion on a proposed chapter plan, grounded in two literal excerpts. */
public record PlanCausalityAudit(List<Finding> findings) {
    public PlanCausalityAudit {
        findings=findings==null?List.of():List.copyOf(findings);
    }

    public record Finding(String location,String problem,String candidateQuote,String basisQuote,
                          String suggestion,String severity) {}

    public Review merge(Review base,ModelGateway.Generated candidate,JsonNode confirmedContext) {
        if(candidate==null || candidate.plan()==null || findings.isEmpty()) return base;
        String current=normalize(PlanNarrativeRenderer.render(candidate.plan()));
        String references=normalize(referenceText(confirmedContext));
        Review result=base;
        int added=0;
        Set<String> reportedCandidateQuotes=new HashSet<>();
        for(Finding finding:findings) {
            if(finding==null || added>=8) break;
            String quoted=normalize(finding.candidateQuote());
            String basis=normalize(finding.basisQuote());
            // An unsupported model allegation must not prevent the author from confirming a plan.
            if(quoted.length()<4 || basis.length()<4 || quoted.equals(basis)
                    || !current.contains(quoted) || !(current.contains(basis)||references.contains(basis))) continue;
            // If a later allegation only cites another wording of the already reported
            // assertion in the same chapter, it adds no independent basis for repair.
            if(reportedCandidateQuotes.contains(basis)
                    && sameChapter(candidate.plan().chapters,quoted,basis)) continue;
            String evidence="当前规划：“"+finding.candidateQuote().strip()+"”；依据：“"+finding.basisQuote().strip()+"”";
            String severity="必须修正".equals(finding.severity())?"必须修正":"作者决定";
            ReviewIssue issue=new ReviewIssue(text(finding.location(),"当前章节规划"),
                    text(finding.problem(),"事件前提、顺序或结果可能不一致"),evidence,
                    text(finding.suggestion(),"请核对并修改当前章节规划，保留已确认正文"),severity);
            var issues=new java.util.ArrayList<>(result.issues()); issues.add(issue.text());
            var details=new java.util.ArrayList<>(result.issueDetails()); details.add(issue);
            result=new Review(result.passed() && !"必须修正".equals(severity),issues,
                    result.mainlineResolved(),result.endingClear(),result.foreshadowingResolved(),details);
            reportedCandidateQuotes.add(quoted);
            added++;
        }
        return result;
    }

    private static boolean sameChapter(List<ChapterBeat> chapters,String first,String second) {
        for(ChapterBeat chapter:chapters) {
            String content=normalize(chapter.title()+"\n"+chapter.purpose()+"\n"
                    +String.join("\n",chapter.sceneBeats())+"\n"+chapter.revealBoundary()+"\n"+chapter.endingHook());
            if(content.contains(first) && content.contains(second)) return true;
        }
        return false;
    }

    private static String referenceText(JsonNode context) {
        if(context==null) return "";
        StringBuilder out=new StringBuilder();
        for(String field:List.of("authoritativeReferences","canonBeforeChapter","previousChapterSummary"))
            appendValues(context.path(field),out);
        return out.toString();
    }

    private static void appendValues(JsonNode node,StringBuilder out) {
        if(node==null || node.isMissingNode() || node.isNull()) return;
        if(node.isTextual()) out.append(node.asText()).append('\n');
        else if(node.isArray()) node.forEach(child->appendValues(child,out));
        else if(node.isObject()) node.elements().forEachRemaining(child->appendValues(child,out));
    }

    private static String normalize(String text) { return text==null?"":text.replaceAll("\\s+","").strip(); }
    private static String text(String value,String fallback) { return value==null||value.isBlank()?fallback:value.strip(); }
}
