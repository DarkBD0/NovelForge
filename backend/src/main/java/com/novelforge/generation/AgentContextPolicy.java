package com.novelforge.generation;

import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/** Deterministic allow-list for the context sections visible to each model role. */
@Component
public class AgentContextPolicy {
    public static final String VERSION="agent-context-v1";
    public static final String STRUCTURED_MEMORY_SHADOW_VERSION="agent-context-structured-memory-shadow-v1";
    public static final String HISTORICAL_CONTINUITY_SHADOW_VERSION="agent-context-historical-continuity-shadow-v2";
    public enum Section {
        PROJECT_BRIEF,
        CONVERSATION_BRIEF,
        STATE_MODEL,
        WORD_BUDGET,
        FORMAL_CANON,
        STRUCTURED_MEMORY,
        CURRENT_PLAN,
        CHAPTER_BRIEF,
        CONFIRMED_DESIGN,
        HISTORICAL_SUMMARIES,
        RECENT_CHAPTER_TEXT,
        RETRIEVED_HISTORY,
        REVISION_TARGET,
        REPAIR_REASONS,
        UPSTREAM_AGENT_OUTPUT
    }

    public record Access(Set<Section> sections,int recentFullChapters) {
        public Access {
            sections=Set.copyOf(sections);
            if(recentFullChapters<0 || recentFullChapters>5) throw new IllegalArgumentException("最近正文窗口必须在 0 到 5 章之间");
        }
        public boolean allows(Section section) { return sections.contains(section); }
    }

    public Access forRole(AgentRole role) {
        return switch(role) {
            case CREATIVE_DIALOGUE -> access(0,Section.PROJECT_BRIEF,Section.CONVERSATION_BRIEF,
                    Section.STATE_MODEL,Section.CONFIRMED_DESIGN,Section.REVISION_TARGET);
            case STORY_ARCHITECT -> access(0,Section.PROJECT_BRIEF,Section.CONVERSATION_BRIEF,
                    Section.STATE_MODEL,Section.WORD_BUDGET,Section.CONFIRMED_DESIGN,Section.REVISION_TARGET,
                    Section.REPAIR_REASONS,Section.UPSTREAM_AGENT_OUTPUT);
            case CHARACTER_WORLD_DESIGNER -> access(0,Section.PROJECT_BRIEF,Section.STATE_MODEL,
                    Section.WORD_BUDGET,Section.CONFIRMED_DESIGN);
            case ROLLING_PLANNER -> access(3,Section.PROJECT_BRIEF,Section.STATE_MODEL,Section.WORD_BUDGET,
                    Section.FORMAL_CANON,Section.CURRENT_PLAN,Section.CONFIRMED_DESIGN,Section.HISTORICAL_SUMMARIES,
                    Section.RECENT_CHAPTER_TEXT,Section.RETRIEVED_HISTORY,Section.REVISION_TARGET,Section.REPAIR_REASONS);
            case CHAPTER_WRITER -> access(3,Section.PROJECT_BRIEF,Section.STATE_MODEL,Section.WORD_BUDGET,
                    Section.FORMAL_CANON,Section.CURRENT_PLAN,Section.CHAPTER_BRIEF,Section.CONFIRMED_DESIGN,
                    Section.HISTORICAL_SUMMARIES,Section.RECENT_CHAPTER_TEXT,Section.RETRIEVED_HISTORY);
            case CONTENT_REVISER -> access(3,Section.PROJECT_BRIEF,Section.STATE_MODEL,Section.WORD_BUDGET,
                    Section.FORMAL_CANON,Section.CURRENT_PLAN,Section.CHAPTER_BRIEF,Section.CONFIRMED_DESIGN,
                    Section.HISTORICAL_SUMMARIES,Section.RECENT_CHAPTER_TEXT,Section.RETRIEVED_HISTORY,
                    Section.REVISION_TARGET,Section.REPAIR_REASONS);
            case STYLE_EDITOR -> access(0,Section.PROJECT_BRIEF,Section.STATE_MODEL,Section.FORMAL_CANON,
                    Section.CURRENT_PLAN,Section.CHAPTER_BRIEF,Section.REVISION_TARGET,Section.REPAIR_REASONS);
            case CONTENT_AUDITOR, CONTINUITY_AUDITOR, PLOT_FORESHADOW_AUDITOR -> access(3,
                    Section.PROJECT_BRIEF,Section.STATE_MODEL,Section.FORMAL_CANON,Section.CURRENT_PLAN,
                    Section.CHAPTER_BRIEF,Section.CONFIRMED_DESIGN,Section.HISTORICAL_SUMMARIES,
                    Section.RECENT_CHAPTER_TEXT,Section.RETRIEVED_HISTORY,Section.REVISION_TARGET);
            case STYLE_AUDITOR -> access(0,Section.PROJECT_BRIEF,Section.STATE_MODEL,Section.FORMAL_CANON,
                    Section.CURRENT_PLAN,Section.CHAPTER_BRIEF,Section.REVISION_TARGET);
            case STATE_EXTRACTOR -> access(0,Section.PROJECT_BRIEF,Section.STATE_MODEL,Section.FORMAL_CANON,
                    Section.CURRENT_PLAN,Section.CHAPTER_BRIEF,Section.CONFIRMED_DESIGN,
                    Section.HISTORICAL_SUMMARIES);
            case COMPLETION_AUDITOR -> access(0,Section.PROJECT_BRIEF,Section.STATE_MODEL,Section.WORD_BUDGET,
                    Section.FORMAL_CANON,Section.CONFIRMED_DESIGN,Section.HISTORICAL_SUMMARIES,
                    Section.RETRIEVED_HISTORY);
        };
    }

    public Access forStructuredMemoryShadow(AgentRole role) {
        Access base=forRole(role);
        if(!Set.of(AgentRole.ROLLING_PLANNER,AgentRole.CHAPTER_WRITER,AgentRole.CONTENT_REVISER,
                AgentRole.CONTENT_AUDITOR,AgentRole.CONTINUITY_AUDITOR,AgentRole.PLOT_FORESHADOW_AUDITOR,
                AgentRole.STATE_EXTRACTOR,AgentRole.COMPLETION_AUDITOR).contains(role)) return base;
        EnumSet<Section> sections=EnumSet.copyOf(base.sections());
        sections.add(Section.STRUCTURED_MEMORY);
        return new Access(sections,base.recentFullChapters());
    }

    private static Access access(int recentFullChapters,Section first,Section... remaining) {
        EnumSet<Section> sections=EnumSet.of(first,remaining);
        return new Access(sections,recentFullChapters);
    }
}
