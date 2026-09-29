package com.novelforge.generation;

import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Review;
import org.springframework.stereotype.Component;

import static com.novelforge.shared.Problem.require;

/**
 * Deterministic boundary between task lifecycle management and model roles.
 *
 * <p>Role-specific calls stay behind this boundary without giving any model
 * control over workflow, persistence, confirmation or downstream routing.</p>
 */
@Component
public class AgentOrchestrator {
    private final ModelGateway gateway;
    private final StyleReviewPolicy styleReviewPolicy;

    public AgentOrchestrator(ModelGateway gateway, StyleReviewPolicy styleReviewPolicy) {
        this.gateway = gateway;
        this.styleReviewPolicy = styleReviewPolicy;
    }

    public ModelGateway.Generated generate(ModelGateway.Request request) {
        generationRole(request.action());
        return gateway.generate(request);
    }

    public ModelGateway.Generated outlineFoundation(ModelGateway.Request request) {
        require(request!=null && request.action()==Action.OUTLINE,"人物世界参谋只用于首次生成大纲");
        return gateway.outlineFoundation(request);
    }

    public Review outlineContinuityReview(ModelGateway.Request request, ModelGateway.Generated candidate) {
        require(candidate!=null,"大纲连贯性检查缺少候选内容");
        return gateway.outlineContinuityReview(request,candidate);
    }

    public Review outlinePlotReview(ModelGateway.Request request, ModelGateway.Generated candidate) {
        require(candidate!=null,"大纲情节伏笔检查缺少候选内容");
        return gateway.outlinePlotReview(request,candidate);
    }

    public Review review(ModelGateway.Request request, ModelGateway.Generated candidate) {
        AgentRole role = reviewRole(request.action());
        if (role == AgentRole.STYLE_AUDITOR) {
            return styleReviewPolicy.normalize(gateway.styleReview(request, candidate), candidate.content());
        }
        return gateway.review(request, candidate);
    }

    public Review continuityReview(ModelGateway.Request request, ModelGateway.Generated candidate) {
        require(candidate != null, "连续性检查缺少候选内容");
        return gateway.continuityReview(request, candidate);
    }

    public Review plotForeshadowReview(ModelGateway.Request request, ModelGateway.Generated candidate) {
        require(candidate != null, "情节与伏笔检查缺少候选内容");
        return gateway.plotForeshadowReview(request, candidate);
    }

    public AgentRole generationRole(Action action) {
        require(action != null, "Agent 操作不能为空");
        return switch (action) {
            case OUTLINE -> AgentRole.STORY_ARCHITECT;
            case CHARACTERS -> AgentRole.CHARACTER_WORLD_DESIGNER;
            case PLAN -> AgentRole.ROLLING_PLANNER;
            case CHAPTER -> AgentRole.CHAPTER_WRITER;
            case REWRITE -> AgentRole.CONTENT_REVISER;
            case REVIEW, STYLE_REVIEW, COMPLETE -> throw new IllegalArgumentException(action + " 不是内容生成操作");
        };
    }

    public AgentRole reviewRole(Action action) {
        require(action != null, "Agent 操作不能为空");
        return switch (action) {
            case STYLE_REVIEW -> AgentRole.STYLE_AUDITOR;
            case COMPLETE -> AgentRole.COMPLETION_AUDITOR;
            default -> AgentRole.CONTENT_AUDITOR;
        };
    }

    public String mode() {
        return gateway.mode();
    }

    public boolean ready() {
        return gateway.ready();
    }
}
