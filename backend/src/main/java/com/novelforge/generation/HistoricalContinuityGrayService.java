package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Review;
import com.novelforge.shared.Problem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Opt-in, non-blocking gray checker that gives the continuity role verified long-range memory.
 * It never replaces the formal review and never changes confirmation permissions.
 */
@Component
public class HistoricalContinuityGrayService {
    public static final String CHECKER="HISTORICAL_CONTINUITY";
    public static final String POLICY_VERSION="historical-continuity-gray-v6";
    private static final ShadowReviewRunner.Spec SPEC=new ShadowReviewRunner.Spec(CHECKER,POLICY_VERSION,
            AgentRole.CONTINUITY_AUDITOR,"shadow-historical-continuity-review",
            "历史连续性灰度检查失败；已自动回退到现有检查，正式结果和候选内容不受影响");

    private final ShadowReviewRunner runner;
    private final HistoricalStructuredMemoryShadowService historical;
    private final HistoricalChapterSelector selector;
    private final RoleContextCompiler compiler;
    private final ModelGateway model;
    private final ContinuityReviewPolicy continuityPolicy;
    private final HistoricalEvidenceReviewPolicy evidencePolicy;
    private final StructuredMemoryContextService memorySelector;
    private final HistoricalClaimEvidencePairingService pairing;
    private final ObjectMapper mapper;
    private final SourceSnapshotFactory hashes;
    private final boolean enabled;
    private final Set<String> novelIds;
    private final int maxChapters;

    public HistoricalContinuityGrayService(ShadowReviewRunner runner,
            HistoricalStructuredMemoryShadowService historical,HistoricalChapterSelector selector,
            RoleContextCompiler compiler,ModelGateway model,ContinuityReviewPolicy continuityPolicy,
            HistoricalEvidenceReviewPolicy evidencePolicy,
            StructuredMemoryContextService memorySelector,HistoricalClaimEvidencePairingService pairing,
            ObjectMapper mapper,SourceSnapshotFactory hashes,
            @Value("${novelforge.experiments.historical-continuity-gray-enabled:false}") boolean enabled,
            @Value("${novelforge.experiments.historical-continuity-gray-novel-ids:}") String novelIds,
            @Value("${novelforge.experiments.historical-continuity-gray-max-chapters:4}") int maxChapters) {
        if(maxChapters<1||maxChapters>20) throw new IllegalArgumentException("历史连续性灰度章节上限必须在 1 到 20 之间");
        this.runner=runner; this.historical=historical; this.selector=selector; this.compiler=compiler;
        this.model=model; this.continuityPolicy=continuityPolicy; this.evidencePolicy=evidencePolicy;
        this.memorySelector=memorySelector; this.pairing=pairing;
        this.mapper=mapper; this.hashes=hashes;
        this.enabled=enabled; this.novelIds=parseNovelIds(novelIds); this.maxChapters=maxChapters;
    }

    public boolean enabled() { return enabled&&historical.enabled(); }
    public List<String> novelIds() { return novelIds.stream().sorted().toList(); }

    public void run(String novelId,String taskId,String sourceSnapshotId,String artifactId,String versionId,
                    ModelGateway.Request base,ModelGateway.Generated candidate,List<String> upstreamAgentRunIds) {
        if(!eligible(novelId)||base==null||candidate==null) return;
        List<Integer> chapters=selector.select(base.novel(),base.context().chapterNumber(),maxChapters);
        if(chapters.isEmpty()) return;
        runner.runDeferred(SPEC,novelId,taskId,sourceSnapshotId,artifactId,versionId,upstreamAgentRunIds,
                ()->historicalRequest(base,versionId,chapters,candidate),candidate,
                request->groundedReview(request,candidate));
    }

    private boolean eligible(String novelId) {
        return enabled() && (novelIds.contains("*")||novelIds.contains(novelId));
    }

    private ModelGateway.Request historicalRequest(ModelGateway.Request base,String versionId,List<Integer> chapters,
            ModelGateway.Generated candidate) {
        String memoryKey="gray-"+hashes.hash(base.novel().id+"\n"+base.novel().revision+"\n"+versionId+"\n"+chapters)
                .substring(0,32);
        HistoricalStructuredMemoryShadowService.Report reconstruction=
                historical.evaluate(base.novel(),memoryKey,chapters);
        var claimPairs=pairing.pair(candidateText(candidate),base.novel(),chapters);
        var aggregate=memorySelector.selectHistorical(reconstruction.aggregate(),candidate.title()+"\n"
                +candidate.content()+"\n"+candidate.summary());
        aggregate=pairing.includePairEvidence(aggregate,claimPairs);
        if(aggregate.facts().isEmpty()&&aggregate.entities().isEmpty()&&aggregate.relations().isEmpty())
            throw new Problem(409,"历史章节没有产生通过证据校验的结构化记忆");
        try {
            ObjectNode root=(ObjectNode)mapper.readTree(base.context().json());
            root.set("formalStructuredMemory",mapper.valueToTree(aggregate));
            root.set("historicalClaimEvidencePairs",mapper.valueToTree(claimPairs));
            LinkedHashSet<String> sources=new LinkedHashSet<>(base.context().sourceVersions());
            aggregate.facts().forEach(item->sources.addAll(item.sourceVersionIds()));
            aggregate.entities().forEach(item->sources.addAll(item.sourceVersionIds()));
            aggregate.relations().forEach(item->sources.addAll(item.sourceVersionIds()));
            ContextAssembler.Context context=new ContextAssembler.Context(mapper.writeValueAsString(root),
                    List.copyOf(sources),base.context().chapterNumber(),base.context().batchNumber());
            String instructions=(base.instructions()==null?"":base.instructions()+"\n")
                    +"历史连续性灰度检查：只检查候选与已确认内容在同一时点的硬冲突。"
                    +"formalStructuredMemory 的归纳描述不是证据；只能逐字引用 evidence[].quote 作为‘已确认依据’。"
                    +"已确认依据必须从某一条 evidence[].quote 原样复制，字词和标点都不能概括、缩写或改写；"
                    +"输出前必须确认引文是 evidence[].quote 的连续子串，否则不要输出该问题。"
                    +"检查时逐条对照候选中的人物、物品、地点、关系、时间和首次发生来源与每条 evidence[].quote；"
                    +"候选出现‘从未、第一次、并非、没有、一直’等排他性陈述时，必须重点核对是否否定了已有证据。"
                    +"historicalClaimEvidencePairs 是本地程序按字面相关性生成的待核对组合，不代表已经冲突；"
                    +"优先逐对判断 candidateClaim 与 historicalQuote 是否指向同一对象和同一属性，"
                    +"只有事实互斥时才能报告。";
            return compiler.compileHistoricalContinuityShadow(new ModelGateway.Request(base.action(),base.novel(),
                    base.target(),context,instructions));
        } catch(Problem failure) { throw failure; }
        catch(Exception failure) { throw new Problem(500,"无法构建历史连续性灰度上下文"); }
    }

    private Review groundedReview(ModelGateway.Request request,ModelGateway.Generated candidate) {
        Review raw=continuityReviewWithOneFormatRetry(request,candidate);
        try {
            var root=mapper.readTree(request.context().json());
            var aggregate=mapper.treeToValue(root.path("formalStructuredMemory"),
                    HistoricalStructuredMemoryShadowService.Aggregate.class);
            Review grounded=evidencePolicy.retainGrounded(raw,aggregate);
            boolean rejected=raw.issueDetails()!=null&&!raw.issueDetails().isEmpty()
                    &&grounded.issueDetails().isEmpty();
            boolean recall=(raw.issueDetails()==null||raw.issueDetails().isEmpty())
                    &&continuityPolicy.requiresRecallRetry(candidate.title()+"\n"+candidate.content()+"\n"
                    +candidate.summary());
            if(rejected||recall) {
                String retryInstructions=(request.instructions()==null?"":request.instructions()+"\n")+(rejected
                        ?"上一次检查发现了疑似冲突，但‘已确认依据’不是 evidence[].quote 的逐字引文，因此被拒绝。"
                        +"这是唯一一次格式纠正重试：如果仍要报告，必须从 evidence[].quote 原样复制连续文字；"
                        +"不能概括或改写。无法逐字引用就返回 issues=[]。"
                        :"上一次检查未报告问题，但候选包含排他性陈述。"
                        +"这是唯一一次召回复核：逐条将‘从未、第一次、首次、唯一、一直’等陈述与每条 evidence[].quote 对照；"
                        +"只有同一对象、同一事实确实互斥时才报告，并逐字引用两侧原文。");
                var retryAggregate=memorySelector.focusHistoricalEvidence(aggregate,candidate.title()+"\n"
                        +candidate.content()+"\n"+candidate.summary(),8);
                var retryRequest=withHistoricalMemory(request,retryInstructions,retryAggregate,candidate);
                Review retry=continuityReviewWithOneFormatRetry(retryRequest,candidate);
                return evidencePolicy.retainGrounded(retry,retryAggregate);
            }
            return grounded;
        } catch(Exception failure) { throw new Problem(500,"无法校验历史连续性检查的引用证据"); }
    }

    private Review continuityReviewWithOneFormatRetry(ModelGateway.Request request,
            ModelGateway.Generated candidate) {
        try { return model.continuityReview(request,candidate); }
        catch(Problem firstFailure) {
            String instructions=(request.instructions()==null?"":request.instructions()+"\n")
                    +"上一次没有返回完整合法的检查 JSON。这是唯一一次格式重试：只输出约定 JSON 对象，"
                    +"字段类型必须正确，不要输出解释、Markdown 或重复字段。";
            var retry=new ModelGateway.Request(request.action(),request.novel(),request.target(),request.context(),
                    instructions);
            return model.continuityReview(retry,candidate);
        }
    }

    private ModelGateway.Request withHistoricalMemory(ModelGateway.Request request,String instructions,
            HistoricalStructuredMemoryShadowService.Aggregate aggregate,ModelGateway.Generated candidate) {
        try {
            ObjectNode root=(ObjectNode)mapper.readTree(request.context().json());
            root.set("formalStructuredMemory",mapper.valueToTree(aggregate));
            root.set("historicalClaimEvidencePairs",mapper.valueToTree(
                    pairing.pair(candidateText(candidate),aggregate)));
            var context=new ContextAssembler.Context(mapper.writeValueAsString(root),request.context().sourceVersions(),
                    request.context().chapterNumber(),request.context().batchNumber());
            return new ModelGateway.Request(request.action(),request.novel(),request.target(),context,instructions);
        } catch(Exception failure) { throw new Problem(500,"无法构建历史连续性聚焦复核上下文"); }
    }

    private String candidateText(ModelGateway.Generated candidate) {
        return candidate.title()+"\n"+candidate.content()+"\n"+(candidate.summary()==null?"":candidate.summary());
    }

    private static Set<String> parseNovelIds(String value) {
        if(value==null||value.isBlank()) return Set.of();
        LinkedHashSet<String> result=new LinkedHashSet<>();
        Arrays.stream(value.split(",")).map(String::strip).filter(item->!item.isBlank()).forEach(result::add);
        return Set.copyOf(result);
    }
}
