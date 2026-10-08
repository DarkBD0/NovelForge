package com.novelforge.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelforge.generation.AgentContextPolicy.Access;
import com.novelforge.generation.AgentContextPolicy.Section;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.shared.Problem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Compiles one immutable task snapshot into a least-privilege view for one role. */
@Component
public class RoleContextCompiler {
    private final ObjectMapper mapper;
    private final AgentContextPolicy policies;

    public RoleContextCompiler(ObjectMapper mapper,AgentContextPolicy policies) {
        this.mapper=mapper; this.policies=policies;
    }

    public ModelGateway.Request compile(ModelGateway.Request request,AgentRole role) {
        return compile(request,role,false);
    }

    public ModelGateway.Request compileStructuredMemoryShadow(ModelGateway.Request request,AgentRole role) {
        return compile(request,role,true);
    }

    private ModelGateway.Request compile(ModelGateway.Request request,AgentRole role,boolean structuredMemoryShadow) {
        if(request==null || request.context()==null || request.context().json()==null) return request;
        try {
            ContextAssembler.Context base=request.context();
            ObjectNode input=(ObjectNode)mapper.readTree(base.json());
            String expectedPolicy=structuredMemoryShadow?AgentContextPolicy.STRUCTURED_MEMORY_SHADOW_VERSION
                    :AgentContextPolicy.VERSION;
            if(role.name().equals(input.path("agentContext").path("role").asText())
                    &&expectedPolicy.equals(input.path("agentContext").path("policyVersion").asText())) return request;
            Access access=structuredMemoryShadow?policies.forStructuredMemoryShadow(role):policies.forRole(role);
            ObjectNode output=mapper.createObjectNode();
            copy(input,output,access,Section.PROJECT_BRIEF,"title","synopsis","requirements");
            copy(input,output,access,Section.CONVERSATION_BRIEF,"conversationBrief");
            copy(input,output,access,Section.STATE_MODEL,"stateModel","planningState");
            copy(input,output,access,Section.WORD_BUDGET,"targetWords","approvedMaxWords","confirmedWords",
                    "nextChapter","nextBatch","remainingWordsToTarget","remainingWordsToApprovedMax");
            copy(input,output,access,Section.FORMAL_CANON,"canonBeforeChapter");
            copy(input,output,access,Section.STRUCTURED_MEMORY,"formalStructuredMemory");
            copy(input,output,access,Section.CURRENT_PLAN,"currentChapterPlan","nextBatchStartChapter",
                    "unwrittenChapterBeforeNewBatch","planningBoundaryNote");
            copy(input,output,access,Section.CHAPTER_BRIEF,"chapterBrief");
            copy(input,output,access,Section.HISTORICAL_SUMMARIES,"previousChapterSummary");
            copy(input,output,access,Section.RETRIEVED_HISTORY,"retrievedConfirmedHistory");
            copy(input,output,access,Section.REVISION_TARGET,"revisionTarget");
            copy(input,output,access,Section.REPAIR_REASONS,"repairReasons");
            copy(input,output,access,Section.UPSTREAM_AGENT_OUTPUT,"outlineFoundation","outlineFoundationAuthority");
            acceptedReferences(input,output,access,base.chapterNumber());

            ObjectNode contract=mapper.createObjectNode();
            contract.put("schemaVersion",1); contract.put("policyVersion",expectedPolicy);
            contract.put("role",role.name());
            contract.put("recentFullChapters",access.recentFullChapters());
            ArrayNode allowed=contract.putArray("allowedSections");
            access.sections().stream().map(Enum::name).sorted().forEach(allowed::add);
            output.set("agentContext",contract);

            Set<String> visibleIds=new LinkedHashSet<>();
            collectVersionIds(output,null,visibleIds);
            List<String> sources=base.sourceVersions().stream().filter(visibleIds::contains).toList();
            ContextAssembler.Context compiled=new ContextAssembler.Context(mapper.writeValueAsString(output),sources,
                    base.chapterNumber(),base.batchNumber());
            return new ModelGateway.Request(request.action(),request.novel(),request.target(),compiled,request.instructions());
        } catch(Exception failure) {
            throw new Problem(500,"无法为 Agent 编译隔离上下文；尚未调用模型");
        }
    }

    private void acceptedReferences(ObjectNode input,ObjectNode output,Access access,int currentChapter) {
        JsonNode references=input.get("acceptedReferences");
        if(references==null || !references.isArray()) return;
        boolean designs=access.allows(Section.CONFIRMED_DESIGN);
        boolean history=access.allows(Section.HISTORICAL_SUMMARIES);
        if(!designs && !history) return;
        ArrayNode filtered=mapper.createArrayNode();
        for(JsonNode reference:references) {
            if(!reference.isObject()) continue;
            boolean chapter=Kind.CHAPTER.name().equals(reference.path("kind").asText());
            if(chapter && !history || !chapter && !designs) continue;
            String authority=reference.path("authorityState").asText();
            if(!authority.isBlank() && !"CONFIRMED".equals(authority)) continue;
            if(chapter && reference.path("chapter").asInt()>=currentChapter) continue;
            ObjectNode visible=reference.deepCopy();
            if(chapter) {
                int number=visible.path("chapter").asInt();
                boolean full=access.allows(Section.RECENT_CHAPTER_TEXT) && access.recentFullChapters()>0
                        && number>=currentChapter-access.recentFullChapters();
                if(!full) visible.remove("content");
            }
            filtered.add(visible);
        }
        if(!filtered.isEmpty()) output.set("acceptedReferences",filtered);
    }

    private static void copy(ObjectNode input,ObjectNode output,Access access,Section section,String... fields) {
        if(!access.allows(section)) return;
        for(String field:fields) if(input.has(field)) output.set(field,input.get(field).deepCopy());
    }

    private static void collectVersionIds(JsonNode node,String field,Set<String> result) {
        if(node==null) return;
        if(node.isValueNode()) {
            if(field!=null && (field.equals("versionId") || field.equals("sourceVersionId")
                    || field.equals("sourceVersionIds") || field.equals("baseVersionId"))) {
                String value=node.asText(); if(!value.isBlank()) result.add(value);
            }
            return;
        }
        if(node.isArray()) node.forEach(item->collectVersionIds(item,field,result));
        else node.fields().forEachRemaining(entry->collectVersionIds(entry.getValue(),entry.getKey(),result));
    }
}
