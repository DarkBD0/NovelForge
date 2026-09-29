package com.novelforge.generation;

/**
 * Logical roles used by the deterministic orchestrator.
 *
 * <p>They are routing labels, not autonomous workers: roles cannot advance the
 * workflow, persist content or invoke another role.</p>
 */
public enum AgentRole {
    STORY_ARCHITECT,
    CHARACTER_WORLD_DESIGNER,
    ROLLING_PLANNER,
    CHAPTER_WRITER,
    CONTENT_REVISER,
    STYLE_EDITOR,
    CONTENT_AUDITOR,
    CONTINUITY_AUDITOR,
    PLOT_FORESHADOW_AUDITOR,
    STYLE_AUDITOR,
    COMPLETION_AUDITOR
}
