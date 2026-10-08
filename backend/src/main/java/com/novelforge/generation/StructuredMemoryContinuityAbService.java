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

/** Two real model calls over the same candidate; the only variable is structured-memory context. */
@Service
public class StructuredMemoryContinuityAbService {
    public record Candidate(String title,String content,String summary) {}
    public record Trial(String contextPolicyVersion,int contextChars,String contextHash,long durationMillis,
                        int rawFindings,int acceptedFindings,Review rawReview,Review review) {}
    public record Report(String status,boolean shadowOnly,String scope,String requestKey,long novelRevision,
                         int beforeChapter,int entities,int relations,Trial official,Trial structuredMemory,
                         List<String> overlappingFindings,List<String> officialOnlyFindings,
                         List<String> structuredMemoryOnlyFindings,String note) {}
    private record Cached(String fingerprint,Report report) {}

    private final ContextAssembler assembler;
    private final StructuredMemoryContextService memories;
    private final RoleContextCompiler compiler;
    private final ModelGateway model;
    private final ReviewAggregator reviews;
    private final ContinuityReviewPolicy continuityPolicy;
    private final ObjectMapper mapper;
    private final SourceSnapshotFactory hashes;
    private final boolean enabled;
    private final ConcurrentHashMap<String,Cached> cache=new ConcurrentHashMap<>();

    public StructuredMemoryContinuityAbService(ContextAssembler assembler,StructuredMemoryContextService memories,
                                               RoleContextCompiler compiler,ModelGateway model,
                                               ReviewAggregator reviews,ContinuityReviewPolicy continuityPolicy,
                                               ObjectMapper mapper,SourceSnapshotFactory hashes,
                                               @Value("${novelforge.experiments.structured-memory-continuity-ab-enabled:false}") boolean enabled) {
        this.assembler=assembler; this.memories=memories; this.compiler=compiler; this.model=model;
        this.reviews=reviews; this.continuityPolicy=continuityPolicy; this.mapper=mapper; this.hashes=hashes;
        this.enabled=enabled;
    }

    public boolean enabled() { return enabled; }

    public Report evaluate(Novel novel,String requestKey,Candidate candidate) {
        require(enabled,"结构化记忆连续性 A/B 尚未启用");
        require(model.ready(),"真实模型尚未配置，不能执行连续性 A/B");
        require(requestKey!=null&&!requestKey.isBlank()&&requestKey.length()<=100,"A/B 评测需要不超过100字符的幂等键");
        require(candidate!=null&&candidate.title()!=null&&!candidate.title().isBlank(),"A/B 候选缺少标题");
        require(candidate.content()!=null&&!candidate.content().isBlank(),"A/B 候选缺少正文");
        String key=novel.id+"|"+requestKey;
        String fingerprint=hashes.hash(novel.revision+"\n"+candidate.title()+"\n"+candidate.content()+"\n"+candidate.summary());
        Cached existing=cache.get(key);
        if(existing!=null) {
            require(existing.fingerprint().equals(fingerprint),"同一 A/B 幂等键不能用于不同候选或小说修订版本");
            return existing.report();
        }
        Cached saved=cache.computeIfAbsent(key,ignored->new Cached(fingerprint,run(novel,requestKey,candidate)));
        require(saved.fingerprint().equals(fingerprint),"同一 A/B 幂等键不能用于不同候选或小说修订版本");
        return saved.report();
    }

    private Report run(Novel novel,String requestKey,Candidate candidate) {
        try {
            ContextAssembler.Context base=assembler.assemble(novel,Action.CHAPTER,null);
            String selectionQuery=candidate.title()+"\n"+candidate.content()+"\n"
                    +(candidate.summary()==null?"":candidate.summary());
            StructuredMemoryContextService.Result memory=memories.beforeChapter(novel,base.chapterNumber(),selectionQuery);
            if(memory.empty()) return new Report("NO_SAMPLES",true,"REAL_MODEL_INPUT_AB",requestKey,novel.revision,
                    base.chapterNumber(),0,0,null,null,List.of(),List.of(),List.of(),
                    "当前章节之前没有已确认的结构化实体或关系；未调用模型");
            ObjectNode root=(ObjectNode)mapper.readTree(base.json());
            root.set("formalStructuredMemory",mapper.valueToTree(memory));
            LinkedHashSet<String> sources=new LinkedHashSet<>(base.sourceVersions());
            sources.addAll(memory.sourceVersionIds());
            ContextAssembler.Context shadowBase=new ContextAssembler.Context(mapper.writeValueAsString(root),
                    List.copyOf(sources),base.chapterNumber(),base.batchNumber());
            ModelGateway.Request official=compiler.compile(new ModelGateway.Request(Action.CHAPTER,novel,null,base,
                    "结构化记忆连续性 A/B：只检查候选与已确认事实是否存在同一时点的硬冲突。"),AgentRole.CONTINUITY_AUDITOR);
            ModelGateway.Request shadow=compiler.compileStructuredMemoryShadow(new ModelGateway.Request(Action.CHAPTER,
                    novel,null,shadowBase,official.instructions()),AgentRole.CONTINUITY_AUDITOR);
            ModelGateway.Generated generated=new ModelGateway.Generated(candidate.title(),candidate.content(),
                    candidate.summary()==null?"":candidate.summary(),List.of(),null);
            Trial officialTrial=trial(official,generated,AgentContextPolicy.VERSION);
            Trial shadowTrial=trial(shadow,generated,AgentContextPolicy.STRUCTURED_MEMORY_SHADOW_VERSION);
            LinkedHashSet<String> officialKeys=keys(officialTrial.review());
            LinkedHashSet<String> shadowKeys=keys(shadowTrial.review());
            List<String> overlap=officialKeys.stream().filter(shadowKeys::contains).toList();
            List<String> officialOnly=officialKeys.stream().filter(key->!shadowKeys.contains(key)).toList();
            List<String> shadowOnly=shadowKeys.stream().filter(key->!officialKeys.contains(key)).toList();
            return new Report("SUCCEEDED",true,"REAL_MODEL_INPUT_AB",requestKey,novel.revision,base.chapterNumber(),
                    memory.entities().size(),memory.relations().size(),officialTrial,shadowTrial,overlap,officialOnly,
                    shadowOnly,"两次调用使用同一候选和同一基础快照；没有保存、修改或确认小说内容");
        } catch(Problem failure) { throw failure; }
        catch(Exception failure) { throw new Problem(502,"结构化记忆连续性 A/B 失败；小说内容未修改"); }
    }

    private Trial trial(ModelGateway.Request request,ModelGateway.Generated candidate,String policyVersion) {
        long started=System.nanoTime();
        Review raw=model.continuityReview(request,candidate);
        long duration=(System.nanoTime()-started)/1_000_000;
        Review normalized=continuityPolicy.normalize(reviews.aggregateShadow(raw,candidate));
        return new Trial(policyVersion,request.context().json().length(),hashes.hash(request.context().json()),duration,
                raw.issueDetails().size(),normalized.issueDetails().size(),raw,normalized);
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
