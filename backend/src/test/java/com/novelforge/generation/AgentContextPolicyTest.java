package com.novelforge.generation;

import org.junit.jupiter.api.Test;

import static com.novelforge.generation.AgentContextPolicy.Section.*;
import static org.assertj.core.api.Assertions.assertThat;

class AgentContextPolicyTest {
    private final AgentContextPolicy policy=new AgentContextPolicy();

    @Test void writerGetsStoryMemoryAndOnlyThreeRecentFullChapters() {
        var access=policy.forRole(AgentRole.CHAPTER_WRITER);
        assertThat(access.sections()).contains(FORMAL_CANON,CURRENT_PLAN,CHAPTER_BRIEF,HISTORICAL_SUMMARIES,
                RECENT_CHAPTER_TEXT,RETRIEVED_HISTORY).doesNotContain(CONVERSATION_BRIEF,REVISION_TARGET);
        assertThat(access.recentFullChapters()).isEqualTo(3);
    }

    @Test void styleRolesCannotReadBroadHistoryOrRetrieval() {
        for(AgentRole role:new AgentRole[]{AgentRole.STYLE_EDITOR,AgentRole.STYLE_AUDITOR}) {
            var access=policy.forRole(role);
            assertThat(access.sections()).contains(FORMAL_CANON,CURRENT_PLAN,REVISION_TARGET)
                    .doesNotContain(HISTORICAL_SUMMARIES,RECENT_CHAPTER_TEXT,RETRIEVED_HISTORY,CONVERSATION_BRIEF);
            assertThat(access.recentFullChapters()).isZero();
        }
    }

    @Test void outlineWritersAndProfessionalOutlineAuditorsCanUseAcceptedIntent() {
        assertThat(policy.forRole(AgentRole.CREATIVE_DIALOGUE).allows(CONVERSATION_BRIEF)).isTrue();
        assertThat(policy.forRole(AgentRole.STORY_ARCHITECT).allows(CONVERSATION_BRIEF)).isTrue();
        assertThat(policy.forRole(AgentRole.CONTENT_AUDITOR).allows(CONVERSATION_BRIEF)).isFalse();
        assertThat(policy.forRole(AgentRole.CONTINUITY_AUDITOR).allows(CONVERSATION_BRIEF)).isTrue();
        assertThat(policy.forRole(AgentRole.PLOT_FORESHADOW_AUDITOR).allows(CONVERSATION_BRIEF)).isTrue();
        assertThat(policy.forRole(AgentRole.STORY_ARCHITECT).allows(OUTLINE_REVISION_BOUNDARY)).isTrue();
        assertThat(policy.forRole(AgentRole.CONTINUITY_AUDITOR).allows(OUTLINE_REVISION_BOUNDARY)).isTrue();
        assertThat(policy.forRole(AgentRole.PLOT_FORESHADOW_AUDITOR).allows(OUTLINE_REVISION_BOUNDARY)).isTrue();
        assertThat(policy.forRole(AgentRole.CHAPTER_WRITER).allows(OUTLINE_REVISION_BOUNDARY)).isFalse();
    }

    @Test void everyRoleHasAnExplicitNonEmptyPolicy() {
        for(AgentRole role:AgentRole.values()) assertThat(policy.forRole(role).sections()).isNotEmpty();
    }

    @Test void approvedStoryEngineFocusIsLimitedToPlanningAndContentReview() {
        assertThat(policy.forRole(AgentRole.ROLLING_PLANNER).allows(PLAN_STORY_ENGINE)).isTrue();
        assertThat(policy.forRole(AgentRole.CONTENT_REVISER).allows(PLAN_STORY_ENGINE)).isTrue();
        assertThat(policy.forRole(AgentRole.CONTENT_AUDITOR).allows(PLAN_STORY_ENGINE)).isTrue();
        assertThat(policy.forRole(AgentRole.CHAPTER_WRITER).allows(PLAN_STORY_ENGINE)).isFalse();
        assertThat(policy.forRole(AgentRole.STYLE_EDITOR).allows(PLAN_STORY_ENGINE)).isFalse();
    }

    @Test void stateExtractorGetsCanonButNoBroadTextOrConversation() {
        var access=policy.forRole(AgentRole.STATE_EXTRACTOR);
        assertThat(access.sections()).contains(FORMAL_CANON,KNOWN_IDENTITIES,CURRENT_PLAN,CHAPTER_BRIEF,CONFIRMED_DESIGN)
                .doesNotContain(CONVERSATION_BRIEF,RECENT_CHAPTER_TEXT,RETRIEVED_HISTORY,REVISION_TARGET);
        assertThat(access.recentFullChapters()).isZero();
    }

    @Test void structuredMemoryIsAddedOnlyToStoryStateRolesInTheShadowPolicy() {
        assertThat(policy.forRole(AgentRole.CHAPTER_WRITER).allows(STRUCTURED_MEMORY)).isFalse();
        assertThat(policy.forStructuredMemoryShadow(AgentRole.CHAPTER_WRITER).allows(STRUCTURED_MEMORY)).isTrue();
        assertThat(policy.forStructuredMemoryShadow(AgentRole.CONTINUITY_AUDITOR).allows(STRUCTURED_MEMORY)).isTrue();
        assertThat(policy.forStructuredMemoryShadow(AgentRole.STATE_EXTRACTOR).allows(STRUCTURED_MEMORY)).isTrue();
        assertThat(policy.forStructuredMemoryShadow(AgentRole.STYLE_EDITOR).allows(STRUCTURED_MEMORY)).isFalse();
        assertThat(policy.forStructuredMemoryShadow(AgentRole.STYLE_AUDITOR).allows(STRUCTURED_MEMORY)).isFalse();
    }
}
