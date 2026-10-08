package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Read-only context A/B. It never calls a model and cannot change workflow state. */
@Service
public class StructuredMemoryContextShadowService {
    public record Comparison(String role,int officialChars,int shadowChars,int addedChars,
                             int entities,int relations,String officialHash,String shadowHash,
                             List<String> addedSourceVersionIds) {}
    public record Report(String status,boolean shadowOnly,String scope,long novelRevision,int beforeChapter,
                         int entities,int relations,List<Comparison> comparisons,String note) {}

    private static final List<AgentRole> ROLES=List.of(AgentRole.ROLLING_PLANNER,AgentRole.CHAPTER_WRITER,
            AgentRole.CONTENT_REVISER,AgentRole.CONTENT_AUDITOR,AgentRole.CONTINUITY_AUDITOR,
            AgentRole.PLOT_FORESHADOW_AUDITOR,AgentRole.STATE_EXTRACTOR,AgentRole.COMPLETION_AUDITOR);
    private final ContextAssembler assembler;
    private final StructuredMemoryContextService memories;
    private final RoleContextCompiler compiler;
    private final ObjectMapper mapper;
    private final SourceSnapshotFactory hashes;
    private final boolean enabled;

    public StructuredMemoryContextShadowService(ContextAssembler assembler,StructuredMemoryContextService memories,
                                                RoleContextCompiler compiler,ObjectMapper mapper,
                                                SourceSnapshotFactory hashes,
                                                @Value("${novelforge.experiments.structured-memory-context-shadow-enabled:true}") boolean enabled) {
        this.assembler=assembler; this.memories=memories; this.compiler=compiler; this.mapper=mapper;
        this.hashes=hashes; this.enabled=enabled;
    }

    public boolean enabled() { return enabled; }

    public Report evaluate(Novel novel) {
        if(!enabled) return new Report("DISABLED",true,"INPUT_CONTEXT_ONLY",novel.revision,0,0,0,List.of(),
                "结构化记忆上下文影子评测未启用");
        try {
            ContextAssembler.Context base=assembler.assemble(novel,Action.CHAPTER,null);
            ObjectNode shadowRoot=(ObjectNode)mapper.readTree(base.json());
            String selectionQuery=String.join("\n",List.of("currentChapterPlan","chapterBrief","previousChapterSummary")
                    .stream().map(shadowRoot::get).filter(java.util.Objects::nonNull).map(Object::toString).toList());
            StructuredMemoryContextService.Result memory=memories.beforeChapter(novel,base.chapterNumber(),selectionQuery);
            if(memory.empty()) return new Report("NO_SAMPLES",true,"INPUT_CONTEXT_ONLY",novel.revision,
                    base.chapterNumber(),0,0,List.of(),"当前章节之前没有已确认的结构化实体或关系");
            shadowRoot.set("formalStructuredMemory",mapper.valueToTree(memory));
            LinkedHashSet<String> sources=new LinkedHashSet<>(base.sourceVersions());
            sources.addAll(memory.sourceVersionIds());
            ContextAssembler.Context shadowBase=new ContextAssembler.Context(mapper.writeValueAsString(shadowRoot),
                    List.copyOf(sources),base.chapterNumber(),base.batchNumber());
            ModelGateway.Request officialRequest=new ModelGateway.Request(Action.CHAPTER,novel,null,base,"");
            ModelGateway.Request shadowRequest=new ModelGateway.Request(Action.CHAPTER,novel,null,shadowBase,"");
            List<Comparison> comparisons=new ArrayList<>();
            for(AgentRole role:ROLES) {
                ModelGateway.Request official=compiler.compile(officialRequest,role);
                ModelGateway.Request shadow=compiler.compileStructuredMemoryShadow(shadowRequest,role);
                List<String> added=shadow.context().sourceVersions().stream()
                        .filter(item->!official.context().sourceVersions().contains(item)).toList();
                comparisons.add(new Comparison(role.name(),official.context().json().length(),shadow.context().json().length(),
                        shadow.context().json().length()-official.context().json().length(),memory.entities().size(),
                        memory.relations().size(),hashes.hash(official.context().json()),hashes.hash(shadow.context().json()),added));
            }
            return new Report("READY",true,"INPUT_CONTEXT_ONLY",novel.revision,base.chapterNumber(),
                    memory.entities().size(),memory.relations().size(),List.copyOf(comparisons),
                    "只比较 Agent 实际输入；未调用模型，未生成、检查或修改任何内容");
        } catch(Exception failure) {
            return new Report("UNAVAILABLE",true,"INPUT_CONTEXT_ONLY",novel.revision,0,0,0,List.of(),
                    "无法编译影子上下文，正式创作链路不受影响："+failure.getMessage());
        }
    }
}
