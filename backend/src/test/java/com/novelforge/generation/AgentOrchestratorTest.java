package com.novelforge.generation;

import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Review;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentOrchestratorTest {
    @Test
    void mapsGenerationActionsToExplicitRolesWithoutCallingTheModel() {
        FakeGateway gateway = new FakeGateway();
        AgentOrchestrator orchestrator = new AgentOrchestrator(gateway, new StyleReviewPolicy());

        assertThat(orchestrator.generationRole(Action.OUTLINE)).isEqualTo(AgentRole.STORY_ARCHITECT);
        assertThat(orchestrator.generationRole(Action.CHARACTERS)).isEqualTo(AgentRole.CHARACTER_WORLD_DESIGNER);
        assertThat(orchestrator.generationRole(Action.PLAN)).isEqualTo(AgentRole.ROLLING_PLANNER);
        assertThat(orchestrator.generationRole(Action.CHAPTER)).isEqualTo(AgentRole.CHAPTER_WRITER);
        assertThat(orchestrator.generationRole(Action.REWRITE)).isEqualTo(AgentRole.CONTENT_REVISER);
        assertThatThrownBy(() -> orchestrator.generationRole(Action.REVIEW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(gateway.totalCalls()).isZero();
    }

    @Test
    void routesWritingAndContentReviewExactlyOnceEach() {
        FakeGateway gateway = new FakeGateway();
        AgentOrchestrator orchestrator = new AgentOrchestrator(gateway, new StyleReviewPolicy());
        ModelGateway.Request request = request(Action.CHAPTER);

        ModelGateway.Generated candidate = orchestrator.generate(request);
        Review review = orchestrator.review(request, candidate);

        assertThat(candidate.content()).isEqualTo("正文");
        assertThat(review.passed()).isTrue();
        assertThat(gateway.generateCalls).isEqualTo(1);
        assertThat(gateway.reviewCalls).isEqualTo(1);
        assertThat(gateway.styleReviewCalls).isZero();
    }

    @Test
    void modelReceivesTheRoleViewInsteadOfTheTaskMasterSnapshot() {
        FakeGateway gateway=new FakeGateway();
        AgentOrchestrator orchestrator=new AgentOrchestrator(gateway,new StyleReviewPolicy());
        String json="{\"title\":\"隔离\",\"conversationBrief\":{\"acceptedDecisions\":[\"隐藏\"]},"
                +"\"retrievedConfirmedHistory\":{\"hits\":[]},\"unrelatedInternalField\":\"不得传递\"}";
        var context=new ContextAssembler.Context(json,List.of(),3,1);

        orchestrator.generate(new ModelGateway.Request(Action.CHAPTER,null,null,context,""));

        assertThat(gateway.lastRequest.context().json()).contains("CHAPTER_WRITER","retrievedConfirmedHistory")
                .doesNotContain("conversationBrief","unrelatedInternalField");
    }

    @Test
    void routesStyleReviewOnlyToStyleGatewayAndKeepsItNonBlocking() {
        FakeGateway gateway = new FakeGateway();
        AgentOrchestrator orchestrator = new AgentOrchestrator(gateway, new StyleReviewPolicy());
        ModelGateway.Generated candidate = gateway.candidate();

        Review review = orchestrator.review(request(Action.STYLE_REVIEW), candidate);

        assertThat(orchestrator.reviewRole(Action.STYLE_REVIEW)).isEqualTo(AgentRole.STYLE_AUDITOR);
        assertThat(review.passed()).isTrue();
        assertThat(review.issueDetails()).allMatch(issue -> !"必须修正".equals(issue.severity()));
        assertThat(gateway.generateCalls).isZero();
        assertThat(gateway.reviewCalls).isZero();
        assertThat(gateway.styleReviewCalls).isEqualTo(1);
    }

    @Test
    void delegatesReadinessModeAndCompletionReviewWithoutExtraCalls() {
        FakeGateway gateway = new FakeGateway();
        AgentOrchestrator orchestrator = new AgentOrchestrator(gateway, new StyleReviewPolicy());

        assertThat(orchestrator.ready()).isTrue();
        assertThat(orchestrator.mode()).isEqualTo("test");
        assertThat(orchestrator.reviewRole(Action.COMPLETE)).isEqualTo(AgentRole.COMPLETION_AUDITOR);
        orchestrator.review(request(Action.COMPLETE), gateway.candidate());
        assertThat(gateway.reviewCalls).isEqualTo(1);
        assertThat(gateway.totalCalls()).isEqualTo(1);
    }

    @Test
    void routesContinuityReviewToItsIndependentGatewayMethod() {
        FakeGateway gateway = new FakeGateway();
        AgentOrchestrator orchestrator = new AgentOrchestrator(gateway, new StyleReviewPolicy());

        Review review=orchestrator.continuityReview(request(Action.CHAPTER),gateway.candidate());

        assertThat(review.passed()).isTrue();
        assertThat(gateway.continuityReviewCalls).isEqualTo(1);
        assertThat(gateway.generateCalls).isZero();
        assertThat(gateway.reviewCalls).isZero();
        assertThat(gateway.styleReviewCalls).isZero();
    }

    @Test
    void routesPlotForeshadowReviewToItsIndependentGatewayMethod() {
        FakeGateway gateway = new FakeGateway();
        AgentOrchestrator orchestrator = new AgentOrchestrator(gateway, new StyleReviewPolicy());

        Review review=orchestrator.plotForeshadowReview(request(Action.OUTLINE),gateway.candidate());

        assertThat(review.passed()).isTrue();
        assertThat(gateway.plotForeshadowReviewCalls).isEqualTo(1);
        assertThat(gateway.generateCalls).isZero();
        assertThat(gateway.reviewCalls).isZero();
        assertThat(gateway.continuityReviewCalls).isZero();
    }

    @Test
    void routesOutlineAdviserAndBothOutlineAuditorsIndependently() {
        FakeGateway gateway=new FakeGateway();
        AgentOrchestrator orchestrator=new AgentOrchestrator(gateway,new StyleReviewPolicy());
        ModelGateway.Request request=request(Action.OUTLINE);

        ModelGateway.Generated foundation=orchestrator.outlineFoundation(request);
        Review continuity=orchestrator.outlineContinuityReview(request,gateway.candidate());
        Review plot=orchestrator.outlinePlotReview(request,gateway.candidate());

        assertThat(foundation.content()).isEqualTo("正文");
        assertThat(continuity.passed()).isTrue();
        assertThat(plot.passed()).isTrue();
        assertThat(gateway.outlineFoundationCalls).isEqualTo(1);
        assertThat(gateway.outlineContinuityCalls).isEqualTo(1);
        assertThat(gateway.outlinePlotCalls).isEqualTo(1);
    }

    @Test
    void routesStateExtractionWithItsOwnLeastPrivilegeContext() {
        FakeGateway gateway=new FakeGateway();
        AgentOrchestrator orchestrator=new AgentOrchestrator(gateway,new StyleReviewPolicy());
        String json="{\"title\":\"状态提取\",\"canonBeforeChapter\":[],"
                +"\"retrievedConfirmedHistory\":{\"hits\":[{\"content\":\"不应传入\"}]},"
                +"\"conversationBrief\":{\"acceptedDecisions\":[\"不应传入\"]}}";
        var request=new ModelGateway.Request(Action.CHAPTER,null,null,
                new ContextAssembler.Context(json,List.of(),2,1),"");

        var result=orchestrator.extractState(request,gateway.candidate());

        assertThat(result.facts()).singleElement().satisfies(item->assertThat(item.key()).isEqualTo("event_1"));
        assertThat(gateway.stateExtractionCalls).isEqualTo(1);
        assertThat(gateway.lastRequest.context().json()).contains("STATE_EXTRACTOR","canonBeforeChapter")
                .doesNotContain("retrievedConfirmedHistory","conversationBrief");
    }

    private ModelGateway.Request request(Action action) {
        return new ModelGateway.Request(action, null, null, null, "");
    }

    private static final class FakeGateway implements ModelGateway {
        int generateCalls;
        int reviewCalls;
        int styleReviewCalls;
        int continuityReviewCalls;
        int plotForeshadowReviewCalls;
        int outlineFoundationCalls;
        int outlineContinuityCalls;
        int outlinePlotCalls;
        int stateExtractionCalls;
        Request lastRequest;

        @Override public Generated generate(Request request) {
            generateCalls++; lastRequest=request;
            return candidate();
        }

        @Override public Review review(Request request, Generated candidate) {
            reviewCalls++;
            return new Review(true, List.of(), false, false, false);
        }

        @Override public Generated outlineFoundation(Request request) {
            outlineFoundationCalls++;
            return candidate();
        }

        @Override public Review outlineContinuityReview(Request request,Generated candidate) {
            outlineContinuityCalls++;
            return new Review(true,List.of(),false,false,false);
        }

        @Override public Review outlinePlotReview(Request request,Generated candidate) {
            outlinePlotCalls++;
            return new Review(true,List.of(),true,true,true);
        }

        @Override public Review styleReview(Request request, Generated candidate) {
            styleReviewCalls++;
            return new Review(false, List.of("表达可以更简洁"), false, false, false);
        }

        @Override public Review continuityReview(Request request, Generated candidate) {
            continuityReviewCalls++;
            return new Review(true,List.of(),false,false,false);
        }

        @Override public Review plotForeshadowReview(Request request, Generated candidate) {
            plotForeshadowReviewCalls++;
            return new Review(true,List.of(),false,false,false);
        }

        @Override public StateExtraction extractState(Request request,Generated candidate) {
            stateExtractionCalls++; lastRequest=request;
            return new StateExtraction(List.of(new ExtractedState("event_1","EVENT","正文中的事件","ACTIVE",
                    List.of("正文"))));
        }

        @Override public String mode() { return "test"; }
        @Override public boolean ready() { return true; }

        Generated candidate() {
            return new Generated("标题", "正文", "摘要", List.of(), null);
        }

        int totalCalls() {
            return generateCalls + reviewCalls + styleReviewCalls + continuityReviewCalls + plotForeshadowReviewCalls
                    + outlineFoundationCalls + outlineContinuityCalls + outlinePlotCalls;
        }
    }
}
