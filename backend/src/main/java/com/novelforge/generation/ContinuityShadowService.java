package com.novelforge.generation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/** Non-blocking experiment for world, character and timeline continuity. */
@Component
public class ContinuityShadowService {
    public static final String CHECKER="CONTINUITY";
    public static final String POLICY_VERSION=ContinuityReviewPolicy.VERSION;
    private static final ShadowReviewRunner.Spec SPEC=new ShadowReviewRunner.Spec(CHECKER,POLICY_VERSION,
            AgentRole.CONTINUITY_AUDITOR,"shadow-continuity-review",
            "连续性影子检查失败；正式检查结果和候选内容不受影响");

    private final ShadowReviewRunner runner;
    private final AgentOrchestrator agents;
    private final boolean enabled;

    public ContinuityShadowService(ShadowReviewRunner runner,AgentOrchestrator agents,
                                   @Value("${novelforge.experiments.continuity-shadow-enabled:false}") boolean enabled) {
        this.runner=runner; this.agents=agents; this.enabled=enabled;
    }

    public boolean enabled() { return enabled; }

    public void run(String novelId,String taskId,String sourceSnapshotId,String artifactId,String versionId,
                    ModelGateway.Request request,ModelGateway.Generated candidate,List<String> upstreamAgentRunIds) {
        if (!enabled || candidate==null) return;
        runner.run(SPEC,novelId,taskId,sourceSnapshotId,artifactId,versionId,upstreamAgentRunIds,request,candidate,
                ()->agents.continuityReview(request,candidate));
    }
}
