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
    public static final String POLICY_VERSION="historical-continuity-gray-v1";
    private static final ShadowReviewRunner.Spec SPEC=new ShadowReviewRunner.Spec(CHECKER,POLICY_VERSION,
            AgentRole.CONTINUITY_AUDITOR,"shadow-historical-continuity-review",
            "历史连续性灰度检查失败；已自动回退到现有检查，正式结果和候选内容不受影响");

    private final ShadowReviewRunner runner;
    private final HistoricalStructuredMemoryShadowService historical;
    private final HistoricalChapterSelector selector;
    private final RoleContextCompiler compiler;
    private final ModelGateway model;
    private final HistoricalEvidenceReviewPolicy evidencePolicy;
    private final ObjectMapper mapper;
    private final SourceSnapshotFactory hashes;
    private final boolean enabled;
    private final Set<String> novelIds;
    private final int maxChapters;

    public HistoricalContinuityGrayService(ShadowReviewRunner runner,
            HistoricalStructuredMemoryShadowService historical,HistoricalChapterSelector selector,
            RoleContextCompiler compiler,ModelGateway model,HistoricalEvidenceReviewPolicy evidencePolicy,
            ObjectMapper mapper,SourceSnapshotFactory hashes,
            @Value("${novelforge.experiments.historical-continuity-gray-enabled:false}") boolean enabled,
            @Value("${novelforge.experiments.historical-continuity-gray-novel-ids:}") String novelIds,
            @Value("${novelforge.experiments.historical-continuity-gray-max-chapters:4}") int maxChapters) {
        if(maxChapters<1||maxChapters>20) throw new IllegalArgumentException("历史连续性灰度章节上限必须在 1 到 20 之间");
        this.runner=runner; this.historical=historical; this.selector=selector; this.compiler=compiler;
        this.model=model; this.evidencePolicy=evidencePolicy; this.mapper=mapper; this.hashes=hashes;
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
                ()->historicalRequest(base,versionId,chapters),candidate,
                request->groundedReview(request,candidate));
    }

    private boolean eligible(String novelId) {
        return enabled() && (novelIds.contains("*")||novelIds.contains(novelId));
    }

    private ModelGateway.Request historicalRequest(ModelGateway.Request base,String versionId,List<Integer> chapters) {
        String memoryKey="gray-"+hashes.hash(base.novel().id+"\n"+base.novel().revision+"\n"+versionId+"\n"+chapters)
                .substring(0,32);
        HistoricalStructuredMemoryShadowService.Report reconstruction=
                historical.evaluate(base.novel(),memoryKey,chapters);
        var aggregate=reconstruction.aggregate();
        if(aggregate.facts().isEmpty()&&aggregate.entities().isEmpty()&&aggregate.relations().isEmpty())
            throw new Problem(409,"历史章节没有产生通过证据校验的结构化记忆");
        try {
            ObjectNode root=(ObjectNode)mapper.readTree(base.context().json());
            root.set("formalStructuredMemory",mapper.valueToTree(aggregate));
            LinkedHashSet<String> sources=new LinkedHashSet<>(base.context().sourceVersions());
            aggregate.facts().forEach(item->sources.addAll(item.sourceVersionIds()));
            aggregate.entities().forEach(item->sources.addAll(item.sourceVersionIds()));
            aggregate.relations().forEach(item->sources.addAll(item.sourceVersionIds()));
            ContextAssembler.Context context=new ContextAssembler.Context(mapper.writeValueAsString(root),
                    List.copyOf(sources),base.context().chapterNumber(),base.context().batchNumber());
            String instructions=(base.instructions()==null?"":base.instructions()+"\n")
                    +"历史连续性灰度检查：只检查候选与已确认内容在同一时点的硬冲突。"
                    +"formalStructuredMemory 的归纳描述不是证据；只能逐字引用 evidence[].quote 作为‘已确认依据’。";
            return compiler.compileStructuredMemoryShadow(new ModelGateway.Request(base.action(),base.novel(),
                    base.target(),context,instructions),AgentRole.CONTINUITY_AUDITOR);
        } catch(Problem failure) { throw failure; }
        catch(Exception failure) { throw new Problem(500,"无法构建历史连续性灰度上下文"); }
    }

    private Review groundedReview(ModelGateway.Request request,ModelGateway.Generated candidate) {
        Review raw=model.continuityReview(request,candidate);
        try {
            var root=mapper.readTree(request.context().json());
            var aggregate=mapper.treeToValue(root.path("formalStructuredMemory"),
                    HistoricalStructuredMemoryShadowService.Aggregate.class);
            return evidencePolicy.retainGrounded(raw,aggregate);
        } catch(Exception failure) { throw new Problem(500,"无法校验历史连续性检查的引用证据"); }
    }

    private static Set<String> parseNovelIds(String value) {
        if(value==null||value.isBlank()) return Set.of();
        LinkedHashSet<String> result=new LinkedHashSet<>();
        Arrays.stream(value.split(",")).map(String::strip).filter(item->!item.isBlank()).forEach(result::add);
        return Set.copyOf(result);
    }
}
