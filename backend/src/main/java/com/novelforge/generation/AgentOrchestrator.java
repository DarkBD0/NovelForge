package com.novelforge.generation;

import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Review;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final RoleContextCompiler contexts;

    public AgentOrchestrator(ModelGateway gateway, StyleReviewPolicy styleReviewPolicy) {
        this(gateway,styleReviewPolicy,new RoleContextCompiler(new ObjectMapper(),new AgentContextPolicy()));
    }

    @Autowired
    public AgentOrchestrator(ModelGateway gateway,StyleReviewPolicy styleReviewPolicy,RoleContextCompiler contexts) {
        this.gateway=gateway; this.styleReviewPolicy=styleReviewPolicy; this.contexts=contexts;
    }

    public ModelGateway.Generated generate(ModelGateway.Request request) {
        AgentRole role=generationRole(request.action());
        return gateway.generate(contexts.compile(request,role));
    }

    public ModelGateway.Request contextFor(ModelGateway.Request request,AgentRole role) {
        return contexts.compile(request,role);
    }

    public ModelGateway.DialogueResponse dialogue(ModelGateway.DialogueRequest request) {
        require(request!=null && request.contextJson()!=null && !request.contextJson().isBlank(),
                "创作对话缺少冻结上下文");
        return gateway.dialogue(request);
    }

    public ModelGateway.DialogueResponse dialogue(ModelGateway.DialogueRequest request,ModelGateway.DialogueStream stream) {
        require(request!=null && request.contextJson()!=null && !request.contextJson().isBlank(),
                "创作对话缺少冻结上下文");
        require(stream!=null,"创作对话缺少流式状态接收器");
        return gateway.dialogue(request,stream);
    }

    public ModelGateway.Generated outlineFoundation(ModelGateway.Request request) {
        require(request!=null && request.action()==Action.OUTLINE,"人物世界参谋只用于首次生成大纲");
        return gateway.outlineFoundation(contexts.compile(request,AgentRole.CHARACTER_WORLD_DESIGNER));
    }

    public Review outlineContinuityReview(ModelGateway.Request request, ModelGateway.Generated candidate) {
        require(candidate!=null,"大纲连贯性检查缺少候选内容");
        return gateway.outlineContinuityReview(contexts.compile(request,AgentRole.CONTINUITY_AUDITOR),candidate);
    }

    public Review outlinePlotReview(ModelGateway.Request request, ModelGateway.Generated candidate) {
        require(candidate!=null,"大纲情节伏笔检查缺少候选内容");
        return gateway.outlinePlotReview(contexts.compile(request,AgentRole.PLOT_FORESHADOW_AUDITOR),candidate);
    }

    public Review review(ModelGateway.Request request, ModelGateway.Generated candidate) {
        AgentRole role = reviewRole(request.action());
        if (role == AgentRole.STYLE_AUDITOR) {
            return styleReviewPolicy.normalize(gateway.styleReview(contexts.compile(request,role), candidate), candidate.content());
        }
        return gateway.review(contexts.compile(request,role), candidate);
    }

    public Review continuityReview(ModelGateway.Request request, ModelGateway.Generated candidate) {
        require(candidate != null, "连续性检查缺少候选内容");
        return gateway.continuityReview(contexts.compile(request,AgentRole.CONTINUITY_AUDITOR), candidate);
    }

    public Review plotForeshadowReview(ModelGateway.Request request, ModelGateway.Generated candidate) {
        require(candidate != null, "情节与伏笔检查缺少候选内容");
        return gateway.plotForeshadowReview(contexts.compile(request,AgentRole.PLOT_FORESHADOW_AUDITOR), candidate);
    }

    public ModelGateway.StateExtraction extractState(ModelGateway.Request request,ModelGateway.Generated candidate) {
        require(candidate!=null && candidate.content()!=null && !candidate.content().isBlank(),
                "状态提取缺少最终章节候选");
        return gateway.extractState(contexts.compile(request,AgentRole.STATE_EXTRACTOR),candidate);
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
