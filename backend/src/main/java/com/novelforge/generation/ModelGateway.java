package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.outline.OutlineSpec;
import java.util.List;

public interface ModelGateway {
    record Request(Action action, Novel novel, Artifact target, ContextAssembler.Context context, String instructions) {}
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
    /** Outline-only adviser output; never persisted as a user-confirmable artifact. */
    Generated outlineFoundation(Request request);
    Review review(Request request, Generated candidate);
    Review outlineContinuityReview(Request request, Generated candidate);
    Review outlinePlotReview(Request request, Generated candidate);
    Review continuityReview(Request request, Generated candidate);
    Review plotForeshadowReview(Request request, Generated candidate);
    Review styleReview(Request request, Generated candidate);
    String mode();
    boolean ready();
}
