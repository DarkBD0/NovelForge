package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.outline.OutlineSpec;
import java.util.List;

public interface ModelGateway {
    record Request(Action action, Novel novel, Artifact target, ContextAssembler.Context context, String instructions) {}
    record DialogueRequest(String contextJson) {}
    record DialogueDecision(String type, String text) {}
    record ProjectUpdateCandidate(String field, String proposedValue, String reason) {}
    record DialogueProposal(String operation, String instructions) {}
    record ExtractedState(String key,String type,String detail,String state,List<String> evidenceQuotes) {
        public ExtractedState {
            evidenceQuotes=evidenceQuotes==null?List.of():List.copyOf(evidenceQuotes);
        }
    }
    record ExtractedEntity(String key,String type,String name,List<String> aliases,String description,
                           List<String> evidenceQuotes) {
        public ExtractedEntity {
            aliases=aliases==null?List.of():List.copyOf(aliases);
            evidenceQuotes=evidenceQuotes==null?List.of():List.copyOf(evidenceQuotes);
        }
    }
    record ExtractedRelation(String key,String fromEntityKey,String type,String toEntityKey,String detail,
                             String state,List<String> evidenceQuotes) {
        public ExtractedRelation {
            evidenceQuotes=evidenceQuotes==null?List.of():List.copyOf(evidenceQuotes);
        }
    }
    record StateExtraction(List<ExtractedState> facts,List<ExtractedEntity> entities,
                           List<ExtractedRelation> relations) {
        public StateExtraction {
            facts=facts==null?List.of():List.copyOf(facts);
            entities=entities==null?List.of():List.copyOf(entities);
            relations=relations==null?List.of():List.copyOf(relations);
        }
        public StateExtraction(List<ExtractedState> facts) { this(facts,List.of(),List.of()); }
    }
    interface DialogueStream {
        void stage(String stage);
        void text(String delta);
        boolean cancelled();
        default void onCancel(Runnable action) {}
    }
    record DialogueResponse(String reply, List<DialogueDecision> decisionCandidates,
                            List<ProjectUpdateCandidate> projectUpdateCandidates,
                            DialogueProposal actionProposal) {
        public DialogueResponse {
            decisionCandidates=decisionCandidates==null?List.of():List.copyOf(decisionCandidates);
            projectUpdateCandidates=projectUpdateCandidates==null?List.of():List.copyOf(projectUpdateCandidates);
        }
        public DialogueResponse(String reply,List<DialogueDecision> decisionCandidates,DialogueProposal actionProposal) {
            this(reply,decisionCandidates,List.of(),actionProposal);
        }
    }
    record Generated(String title, String content, String summary, List<Fact> facts, Plan plan,
                     OutlineSpec outlineSpec, List<String> draftIssues) {
        public Generated {
            facts=facts==null?List.of():List.copyOf(facts);
            draftIssues=draftIssues==null?List.of():List.copyOf(draftIssues);
        }
        public Generated(String title, String content, String summary, List<Fact> facts, Plan plan) {
            this(title,content,summary,facts,plan,null,List.of());
        }
        public Generated(String title, String content, String summary, List<Fact> facts, Plan plan,
                         List<String> draftIssues) {
            this(title,content,summary,facts,plan,null,draftIssues);
        }
    }
    Generated generate(Request request);
    default DialogueResponse dialogue(DialogueRequest request) {
        throw new UnsupportedOperationException("当前模型网关未提供创作对话能力");
    }
    default DialogueResponse dialogue(DialogueRequest request, DialogueStream stream) {
        stream.stage("GENERATING");
        DialogueResponse response=dialogue(request);
        if (response.reply()!=null&&!response.reply().isBlank()) stream.text(response.reply());
        stream.stage("FINALIZING");
        return response;
    }
    /** Outline-only adviser output; never persisted as a user-confirmable artifact. */
    Generated outlineFoundation(Request request);
    Review review(Request request, Generated candidate);
    Review outlineContinuityReview(Request request, Generated candidate);
    Review outlinePlotReview(Request request, Generated candidate);
    Review continuityReview(Request request, Generated candidate);
    Review plotForeshadowReview(Request request, Generated candidate);
    Review styleReview(Request request, Generated candidate);
    default StateExtraction extractState(Request request,Generated candidate) {
        throw new UnsupportedOperationException("当前模型网关未提供独立状态提取能力");
    }
    String mode();
    boolean ready();
}
