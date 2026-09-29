package com.novelforge.generation;

import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import com.novelforge.novel.Novel.ShadowReview;
import com.novelforge.novel.Novel.ShadowReviewDecision;
import com.novelforge.novel.Novel.ShadowReviewStatus;
import com.novelforge.novel.Novel.Version;
import com.novelforge.shared.Problem;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.novelforge.shared.Problem.require;

/** Read-only comparison statistics plus author feedback for non-blocking checker experiments. */
@Component
public class ShadowReviewEvaluationService {
    public record ReportView(
            String id,String checker,String targetLabel,ShadowReviewStatus status,
            int blockingFindings,int advisoryFindings,int formalBlockingFindings,
            int overlappingFindings,int uniqueFindings,
            Long turnaroundMillis,Long modelDurationMillis,
            ShadowReviewDecision authorDecision,String authorNote,
            Review review,String error,String createdAt,String finishedAt,
            boolean evaluationSample,String sampleGroup) {}

    public record CheckerSummary(
            String checker,int reports,int succeeded,int failed,
            int blockingFindings,int overlappingFindings,int uniqueFindings,
            int useful,int partlyUseful,int notUseful,int unreviewed,
            int sampleSize,int sampleReviewed,int samplePending,
            Long averageTurnaroundMillis,Long averageModelDurationMillis) {}

    public record Summary(
            int totalReports,int running,int succeeded,int failed,int interrupted,
            int continuityReports,int plotForeshadowReports,int modelCalls,
            int formalBlockingFindings,int shadowBlockingFindings,
            int overlappingFindings,int uniqueShadowFindings,
            int useful,int partlyUseful,int notUseful,int unreviewed,
            int sampleSize,int sampleReviewed,int samplePending,
            Long averageTurnaroundMillis,Long averageModelDurationMillis,
            boolean tokenUsageAvailable,boolean costAvailable,
            List<CheckerSummary> checkerSummaries,
            List<ReportView> reports) {}

    private final NovelRepository repository;
    private final ReviewConvergence convergence;

    public ShadowReviewEvaluationService(NovelRepository repository,ReviewConvergence convergence) {
        this.repository=repository;
        this.convergence=convergence;
    }

    public Summary summary(Novel novel) {
        List<ShadowReview> source=novel.shadowReviews==null?List.of():novel.shadowReviews;
        List<ReportView> reports=new ArrayList<>();
        Map<String,String> sampleGroups=new HashMap<>();
        Set<String> formalFindingKeys=new LinkedHashSet<>();
        List<Long> turnarounds=new ArrayList<>();
        List<Long> modelDurations=new ArrayList<>();
        int running=0,succeeded=0,failed=0,interrupted=0,continuity=0,plot=0,calls=0;
        int shadowBlocking=0,overlap=0,unique=0,useful=0,partial=0,notUseful=0;

        for (ShadowReview shadow:source) {
            if (shadow==null) continue;
            if (shadow.status==ShadowReviewStatus.RUNNING) running++;
            else if (shadow.status==ShadowReviewStatus.SUCCEEDED) succeeded++;
            else if (shadow.status==ShadowReviewStatus.FAILED) failed++;
            else if (shadow.status==ShadowReviewStatus.INTERRUPTED) interrupted++;
            if (ContinuityShadowService.CHECKER.equals(shadow.checker)) continuity++;
            if (PlotForeshadowShadowService.CHECKER.equals(shadow.checker)) plot++;
            if (present(shadow.modelStartedAt)) calls++;
            if (shadow.authorDecision==ShadowReviewDecision.USEFUL) useful++;
            else if (shadow.authorDecision==ShadowReviewDecision.PARTLY_USEFUL) partial++;
            else if (shadow.authorDecision==ShadowReviewDecision.NOT_USEFUL) notUseful++;

            LocatedVersion located=locate(novel,shadow);
            List<ReviewIssue> formalIssues=blocking(located.version==null?null:located.version.review);
            List<ReviewIssue> shadowIssues=blocking(shadow.review);
            List<ReviewIssue> advisory=advisory(shadow.review);
            formalIssues.forEach(issue->formalFindingKeys.add(shadow.versionId+"|"+identity(issue)));
            int repeated=overlapCount(formalIssues,shadowIssues);
            int novelFindings=Math.max(0,shadowIssues.size()-repeated);
            shadowBlocking+=shadowIssues.size(); overlap+=repeated; unique+=novelFindings;
            Long turnaround=elapsed(shadow.createdAt,shadow.finishedAt);
            Long modelDuration=elapsed(shadow.modelStartedAt,shadow.finishedAt);
            if (turnaround!=null) turnarounds.add(turnaround);
            if (modelDuration!=null) modelDurations.add(modelDuration);
            reports.add(new ReportView(shadow.id,shadow.checker,located.label,shadow.status,
                    shadowIssues.size(),advisory.size(),formalIssues.size(),repeated,novelFindings,
                    turnaround,modelDuration,shadow.authorDecision,shadow.authorNote,
                    shadow.review,shadow.error,shadow.createdAt,shadow.finishedAt,false,null));
            sampleGroups.put(shadow.id,sampleGroup(novel,located,shadow.checker,!shadowIssues.isEmpty()));
        }
        reports.sort(Comparator.comparing(ReportView::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        reports=markEvaluationSample(reports,sampleGroups);
        int decided=useful+partial+notUseful;
        int sampleSize=(int)reports.stream().filter(ReportView::evaluationSample).count();
        int sampleReviewed=(int)reports.stream().filter(ReportView::evaluationSample)
                .filter(report->report.authorDecision()!=null).count();
        List<CheckerSummary> checkerSummaries=List.of(
                checkerSummary(ContinuityShadowService.CHECKER,reports),
                checkerSummary(PlotForeshadowShadowService.CHECKER,reports));
        return new Summary(reports.size(),running,succeeded,failed,interrupted,continuity,plot,calls,
                formalFindingKeys.size(),shadowBlocking,overlap,unique,useful,partial,notUseful,
                Math.max(0,succeeded-decided),sampleSize,sampleReviewed,sampleSize-sampleReviewed,
                average(turnarounds),average(modelDurations),false,false,checkerSummaries,List.copyOf(reports));
    }

    public Novel feedback(String novelId,String reviewId,String decision,String note) {
        ShadowReviewDecision parsed=parseDecision(decision);
        return repository.update(novelId,novel->{
            require(novel.shadowReviews!=null,"专业检查报告不存在");
            ShadowReview report=novel.shadowReviews.stream()
                    .filter(item->item!=null && reviewId.equals(item.id)).findFirst()
                    .orElseThrow(()->new Problem(404,"专业检查报告不属于该小说或不存在"));
            require(report.status==ShadowReviewStatus.SUCCEEDED,"只有已完成的专业检查报告可以评价");
            report.authorDecision=parsed;
            report.authorNote=note==null?"":note.strip();
            report.decidedAt=Novel.now();
            return novel;
        });
    }

    private ShadowReviewDecision parseDecision(String value) {
        try { return ShadowReviewDecision.valueOf(value==null?"":value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new Problem(400,"评价只能选择：有帮助、部分有帮助或没有帮助"); }
    }

    private record LocatedVersion(Version version,String label,Artifact artifact) {}

    private LocatedVersion locate(Novel novel,ShadowReview shadow) {
        Artifact artifact=novel.artifacts.stream().filter(item->item.id.equals(shadow.artifactId)).findFirst().orElse(null);
        if (artifact==null) return new LocatedVersion(null,"历史候选版本",null);
        Version version=artifact.versions.stream().filter(item->item.id.equals(shadow.versionId)).findFirst().orElse(null);
        String label=switch (artifact.kind) {
            case OUTLINE -> "全书大纲";
            case CHARACTERS -> "人物设定";
            case PLAN -> "第"+artifact.batchNumber+"批章节规划";
            case CHAPTER -> "第"+artifact.chapterNumber+"章正文";
        };
        return new LocatedVersion(version,label,artifact);
    }

    private String sampleGroup(Novel novel,LocatedVersion located,String checker,boolean hasBlocking) {
        if (located.artifact==null || checker==null) return null;
        String target=switch (located.artifact.kind) {
            case OUTLINE -> "全书大纲";
            case CHARACTERS -> "人物设定";
            case PLAN -> "章节规划";
            case CHAPTER -> chapterBand(novel,located.artifact.chapterNumber);
        };
        String checkerLabel=ContinuityShadowService.CHECKER.equals(checker)?"连续性检查"
                :PlotForeshadowShadowService.CHECKER.equals(checker)?"情节与伏笔检查":"专业检查";
        return target+"|"+checkerLabel+"|"+(hasBlocking?"发现问题":"未发现问题");
    }

    private String chapterBand(Novel novel,int chapterNumber) {
        int max=novel.artifacts.stream().filter(item->item.kind==Novel.Kind.CHAPTER)
                .mapToInt(item->item.chapterNumber).max().orElse(Math.max(1,chapterNumber));
        int first=Math.max(1,(int)Math.ceil(max/3.0));
        int second=Math.max(first+1,(int)Math.ceil(max*2/3.0));
        if (chapterNumber<=first) return "前段正文";
        if (chapterNumber<=second) return "中段正文";
        return "后段正文";
    }

    private List<ReportView> markEvaluationSample(List<ReportView> reports,Map<String,String> groups) {
        Set<String> selected=new HashSet<>();
        List<ReportView> result=new ArrayList<>(reports.size());
        for (ReportView report:reports) {
            String group=groups.get(report.id());
            boolean sample=report.status()==ShadowReviewStatus.SUCCEEDED && group!=null && selected.add(group);
            result.add(new ReportView(report.id(),report.checker(),report.targetLabel(),report.status(),
                    report.blockingFindings(),report.advisoryFindings(),report.formalBlockingFindings(),
                    report.overlappingFindings(),report.uniqueFindings(),report.turnaroundMillis(),
                    report.modelDurationMillis(),report.authorDecision(),report.authorNote(),report.review(),
                    report.error(),report.createdAt(),report.finishedAt(),sample,sample?group:null));
        }
        return result;
    }

    private CheckerSummary checkerSummary(String checker,List<ReportView> reports) {
        List<ReportView> selected=reports.stream().filter(report->checker.equals(report.checker())).toList();
        int succeeded=(int)selected.stream().filter(report->report.status()==ShadowReviewStatus.SUCCEEDED).count();
        int useful=(int)selected.stream().filter(report->report.authorDecision()==ShadowReviewDecision.USEFUL).count();
        int partial=(int)selected.stream().filter(report->report.authorDecision()==ShadowReviewDecision.PARTLY_USEFUL).count();
        int notUseful=(int)selected.stream().filter(report->report.authorDecision()==ShadowReviewDecision.NOT_USEFUL).count();
        List<ReportView> sample=selected.stream().filter(ReportView::evaluationSample).toList();
        List<Long> turnarounds=selected.stream().map(ReportView::turnaroundMillis).filter(value->value!=null).toList();
        List<Long> modelDurations=selected.stream().map(ReportView::modelDurationMillis).filter(value->value!=null).toList();
        int sampleReviewed=(int)sample.stream().filter(report->report.authorDecision()!=null).count();
        return new CheckerSummary(checker,selected.size(),succeeded,
                (int)selected.stream().filter(report->report.status()==ShadowReviewStatus.FAILED).count(),
                selected.stream().mapToInt(ReportView::blockingFindings).sum(),
                selected.stream().mapToInt(ReportView::overlappingFindings).sum(),
                selected.stream().mapToInt(ReportView::uniqueFindings).sum(),useful,partial,notUseful,
                Math.max(0,succeeded-useful-partial-notUseful),sample.size(),sampleReviewed,
                sample.size()-sampleReviewed,average(turnarounds),average(modelDurations));
    }

    private List<ReviewIssue> blocking(Review review) {
        if (review==null || review.issueDetails()==null) return List.of();
        return review.issueDetails().stream().filter(item->item!=null && "必须修正".equals(item.severity())).toList();
    }

    private List<ReviewIssue> advisory(Review review) {
        if (review==null || review.issueDetails()==null) return List.of();
        return review.issueDetails().stream().filter(item->item!=null && !"必须修正".equals(item.severity())).toList();
    }

    private int overlapCount(List<ReviewIssue> formal,List<ReviewIssue> shadow) {
        List<ReviewIssue> unmatched=new ArrayList<>(formal);
        int overlaps=0;
        for (ReviewIssue candidate:shadow) {
            int matched=-1;
            for (int i=0;i<unmatched.size();i++) {
                if (convergence.sameIssueForComparison(candidate,unmatched.get(i))) { matched=i; break; }
            }
            if (matched>=0) { overlaps++; unmatched.remove(matched); }
        }
        return overlaps;
    }

    private String identity(ReviewIssue issue) {
        if (issue.issueId()!=null && !issue.issueId().isBlank()) return issue.issueId();
        return Integer.toHexString((issue.location()+"|"+issue.problem()+"|"+issue.evidence()).hashCode());
    }

    private Long elapsed(String start,String end) {
        if (!present(start) || !present(end)) return null;
        try { return Math.max(0,Duration.between(Instant.parse(start),Instant.parse(end)).toMillis()); }
        catch (DateTimeParseException e) { return null; }
    }

    private Long average(List<Long> values) {
        if (values.isEmpty()) return null;
        return Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private boolean present(String value) { return value!=null && !value.isBlank(); }
}
