package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import com.novelforge.shared.Problem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static com.novelforge.shared.Problem.require;

/**
 * Compares one candidate twice in the same request: first with the official context, then with an
 * ephemeral memory reconstructed from selected confirmed historical chapters.
 */
@Service
public class HistoricalStructuredMemoryContinuityAbService {
    public static final String POLICY_VERSION="historical-structured-memory-continuity-ab-v1";
    public record Candidate(String title,String content,String summary) {}
    public record Trial(String contextPolicyVersion,int contextChars,String contextHash,long durationMillis,
                        int rawFindings,int acceptedFindings,Review rawReview,Review review) {}
    public record Report(String status,boolean shadowOnly,String scope,String policyVersion,String requestKey,
                         long novelRevision,int beforeChapter,List<Integer> requestedChapters,
                         HistoricalStructuredMemoryShadowService.Report reconstruction,
                         Trial official,Trial historicalMemory,List<String> overlappingFindings,
                         List<String> officialOnlyFindings,List<String> historicalMemoryOnlyFindings,String note) {}
    private record Cached(String fingerprint,Report report) {}

    private final ContextAssembler assembler;
    private final HistoricalStructuredMemoryShadowService historical;
    private final RoleContextCompiler compiler;
    private final ModelGateway model;
    private final ReviewAggregator reviews;
    private final ContinuityReviewPolicy continuityPolicy;
    private final HistoricalEvidenceReviewPolicy historicalEvidence;
    private final ObjectMapper mapper;
    private final SourceSnapshotFactory hashes;
    private final boolean enabled;
    private final ConcurrentHashMap<String,Cached> cache=new ConcurrentHashMap<>();

    public HistoricalStructuredMemoryContinuityAbService(ContextAssembler assembler,
            HistoricalStructuredMemoryShadowService historical,RoleContextCompiler compiler,ModelGateway model,
            ReviewAggregator reviews,ContinuityReviewPolicy continuityPolicy,ObjectMapper mapper,
            HistoricalEvidenceReviewPolicy historicalEvidence,SourceSnapshotFactory hashes,
            @Value("${novelforge.experiments.historical-structured-memory-continuity-ab-enabled:false}") boolean enabled) {
        this.assembler=assembler; this.historical=historical; this.compiler=compiler; this.model=model;
        this.reviews=reviews; this.continuityPolicy=continuityPolicy; this.mapper=mapper;
        this.historicalEvidence=historicalEvidence; this.hashes=hashes;
        this.enabled=enabled;
    }

    public boolean enabled() { return enabled; }

    public Report evaluate(Novel novel,String requestKey,List<Integer> chapterNumbers,Candidate candidate) {
        require(enabled,"历史结构化记忆连续性 A/B 尚未启用");
        require(historical.enabled(),"历史结构化记忆影子重建尚未启用");
        require(model.ready(),"真实模型尚未配置，不能执行历史连续性 A/B");
        require(requestKey!=null&&!requestKey.isBlank()&&requestKey.length()<=100,
                "历史连续性 A/B 需要不超过 100 字符的幂等键");
        require(chapterNumbers!=null&&!chapterNumbers.isEmpty(),"至少选择一个历史章节");
        require(candidate!=null&&candidate.title()!=null&&!candidate.title().isBlank(),"A/B 候选缺少标题");
        require(candidate.content()!=null&&!candidate.content().isBlank(),"A/B 候选缺少正文");
        List<Integer> chapters=chapterNumbers.stream().distinct().sorted().toList();
        String fingerprint=hashes.hash(novel.revision+"\n"+chapters+"\n"+candidate.title()+"\n"
                +candidate.content()+"\n"+candidate.summary());
        String key=novel.id+"|"+requestKey;
        Cached existing=cache.get(key);
        if(existing!=null) {
            require(existing.fingerprint().equals(fingerprint),"同一历史连续性 A/B 幂等键不能用于不同输入");
            return existing.report();
        }
        Cached saved=cache.computeIfAbsent(key,ignored->new Cached(fingerprint,
                run(novel,requestKey,chapters,candidate)));
        require(saved.fingerprint().equals(fingerprint),"同一历史连续性 A/B 幂等键不能用于不同输入");
        return saved.report();
    }

    private Report run(Novel novel,String requestKey,List<Integer> chapters,Candidate candidate) {
        try {
            String memoryKey="continuity-ab-memory-"+hashes.hash(novel.id+"\n"+novel.revision+"\n"+chapters)
                    .substring(0,32);
            HistoricalStructuredMemoryShadowService.Report reconstruction=
                    historical.evaluate(novel,memoryKey,chapters);
            if(reconstruction.aggregate().facts().isEmpty()&&reconstruction.aggregate().entities().isEmpty()
                    &&reconstruction.aggregate().relations().isEmpty()) {
                return new Report("NO_SAMPLES",true,"REAL_MODEL_INPUT_AB",POLICY_VERSION,requestKey,
                        novel.revision,0,chapters,reconstruction,null,null,List.of(),List.of(),List.of(),
                        "所选历史章节没有产生任何通过证据校验的影子记忆；未执行连续性模型调用");
            }
            ContextAssembler.Context base=assembler.assemble(novel,Action.CHAPTER,null);
            ObjectNode root=(ObjectNode)mapper.readTree(base.json());
            root.set("formalStructuredMemory",mapper.valueToTree(reconstruction.aggregate()));
            LinkedHashSet<String> sources=new LinkedHashSet<>(base.sourceVersions());
            reconstruction.aggregate().facts().forEach(item->sources.addAll(item.sourceVersionIds()));
            reconstruction.aggregate().entities().forEach(item->sources.addAll(item.sourceVersionIds()));
            reconstruction.aggregate().relations().forEach(item->sources.addAll(item.sourceVersionIds()));
            ContextAssembler.Context shadowBase=new ContextAssembler.Context(mapper.writeValueAsString(root),
                    List.copyOf(sources),base.chapterNumber(),base.batchNumber());
            String instructions="历史长篇连续性 A/B：只检查候选与已确认内容在同一时点是否存在硬冲突。"
                    +"formalStructuredMemory 中 description/detail 是模型归纳，不可单独作为已确认依据；"
                    +"其中 evidence[].quote 已经由本地程序逐字核对，确实来自 sourceVersionId 对应的已确认章节正文，"
                    +"可以作为‘已确认依据’逐字引用。只能依据 evidence[].quote 报告冲突，不得引用归纳描述举证。";
            ModelGateway.Request official=compiler.compile(new ModelGateway.Request(Action.CHAPTER,novel,null,base,
                    instructions),AgentRole.CONTINUITY_AUDITOR);
            ModelGateway.Request shadow=compiler.compileStructuredMemoryShadow(new ModelGateway.Request(Action.CHAPTER,
                    novel,null,shadowBase,instructions),AgentRole.CONTINUITY_AUDITOR);
            ModelGateway.Generated generated=new ModelGateway.Generated(candidate.title(),candidate.content(),
                    candidate.summary()==null?"":candidate.summary(),List.of(),null);
            Trial officialTrial=trial(official,generated,AgentContextPolicy.VERSION);
            Trial shadowTrial=groundedTrial(
                    trial(shadow,generated,AgentContextPolicy.STRUCTURED_MEMORY_SHADOW_VERSION),
                    reconstruction.aggregate());
            LinkedHashSet<String> officialKeys=keys(officialTrial.review());
            LinkedHashSet<String> shadowKeys=keys(shadowTrial.review());
            List<String> overlap=officialKeys.stream().filter(shadowKeys::contains).toList();
            List<String> officialOnly=officialKeys.stream().filter(item->!shadowKeys.contains(item)).toList();
            List<String> shadowOnly=shadowKeys.stream().filter(item->!officialKeys.contains(item)).toList();
            String status="PARTIAL".equals(reconstruction.status())?"SUCCEEDED_WITH_PARTIAL_MEMORY":"SUCCEEDED";
            return new Report(status,true,"REAL_MODEL_INPUT_AB",POLICY_VERSION,requestKey,novel.revision,
                    base.chapterNumber(),chapters,reconstruction,officialTrial,shadowTrial,overlap,officialOnly,
                    shadowOnly,"两次连续性检查使用同一候选和同一基础快照；历史影子记忆只进入第二次检查；"
                            +"没有保存、修改或确认小说内容");
        } catch(Problem failure) { throw failure; }
        catch(Exception failure) { throw new Problem(502,"历史结构化记忆连续性 A/B 失败；小说内容未修改"); }
    }

    private Trial trial(ModelGateway.Request request,ModelGateway.Generated candidate,String policyVersion) {
        long started=System.nanoTime();
        Review raw=model.continuityReview(request,candidate);
        long duration=(System.nanoTime()-started)/1_000_000;
        Review normalized=continuityPolicy.normalize(reviews.aggregateShadow(raw,candidate));
        return new Trial(policyVersion,request.context().json().length(),hashes.hash(request.context().json()),duration,
                raw.issueDetails().size(),normalized.issueDetails().size(),raw,normalized);
    }

    private Trial groundedTrial(Trial trial,HistoricalStructuredMemoryShadowService.Aggregate aggregate) {
        Review review=historicalEvidence.retainGrounded(trial.review(),aggregate);
        return new Trial(trial.contextPolicyVersion(),trial.contextChars(),trial.contextHash(),
                trial.durationMillis(),trial.rawFindings(),review.issueDetails().size(),trial.rawReview(),review);
    }

    private LinkedHashSet<String> keys(Review review) {
        LinkedHashSet<String> result=new LinkedHashSet<>();
        if(review==null||review.issueDetails()==null) return result;
        for(ReviewIssue issue:review.issueDetails()) {
            String key=issue.issueId();
            if(key==null||key.isBlank()) key=hashes.hash(issue.location()+"\n"+issue.problem());
            result.add(key);
        }
        return result;
    }
}
