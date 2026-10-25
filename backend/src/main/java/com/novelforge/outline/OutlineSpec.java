package com.novelforge.outline;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/** Machine-readable design contract for one outline version. */
public class OutlineSpec {
    public String schemaVersion="1";
    public String storyCore;
    /** The repeatable reader-facing hook; called “核心吸引点” in the author UI. */
    public String readerAttraction;
    /** For payoff-driven genres, the first concrete advantage realized in the opening event. */
    public String openingPayoff;
    public String openingPayoffEventId;
    public String genreTone;
    public String protagonistGoal;
    public String coreConflict;
    public String endingContract;
    /** Optional for older approved outlines; concrete proof obligations for the ending. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<EndingGoal> endingGoals=new ArrayList<>();
    public List<RequirementTrace> requirements=new ArrayList<>();
    public List<WorldRule> worldRules=new ArrayList<>();
    public List<CharacterArc> characters=new ArrayList<>();
    public List<StoryPart> parts=new ArrayList<>();
    public List<Foreshadow> foreshadows=new ArrayList<>();
    public List<StoryThread> threads=new ArrayList<>();
    public List<Constraint> constraints=new ArrayList<>();
    public List<String> creativeAssumptions=new ArrayList<>();

    public static class EndingGoal {
        public String id;
        /** A story question or outcome that the ending must answer. */
        public String goal;
        /** What the confirmed prose must establish, appropriate to this genre. */
        public String proofStandard;
    }

    public static class RequirementTrace {
        public String id;
        public String text;
        public String source;
        /** EXPLICIT or ASSUMPTION. */
        public String type;
    }

    public static class WorldRule {
        public String id;
        public String rule;
        public String scope;
        public String limit;
        public String cost;
        public String exceptionRule;
        /** HARD, DIRECTION or OPEN. */
        public String constraintLevel;
        /** CANDIDATE before confirmation; CONFIRMED only after the version is approved. */
        public String authorityStatus;
    }

    public static class CharacterArc {
        public String id;
        public String name;
        /** PROTAGONIST, CORE_SUPPORT or FUNCTIONAL_NPC. */
        public String roleTier;
        public String goal;
        public String motivation;
        public String flaw;
        public String storyFunction;
        public String keyChoice;
        public String arcDirection;
        public String finalState;
    }

    public static class StoryPart {
        public String id;
        public String title;
        public String goal;
        public String conflict;
        public String turn;
        public int estimatedWords;
        public List<StoryStage> stages=new ArrayList<>();
    }

    public static class StoryStage {
        public String id;
        public String title;
        public String goal;
        public String readerPayoff;
        /** The concrete opposition, consequential choice and cost driving this stage. */
        public String dramaticTurn;
        public String payoffResult;
        public String escalation;
        public List<String> prerequisiteIds=new ArrayList<>();
        public int estimatedWords;
        public List<KeyEvent> events=new ArrayList<>();
    }

    public static class KeyEvent {
        public String id;
        public String title;
        public String event;
        public String consequence;
        public String characterChange;
        public String irreversibleTurn;
    }

    public static class Foreshadow {
        public String id;
        public String clue;
        public String setupStageId;
        public List<String> developmentStageIds=new ArrayList<>();
        public String recoveryStageId;
        public String serviceGoal;
    }

    public static class StoryThread {
        public String id;
        public String title;
        public String serviceGoal;
        public String resolutionStageId;
    }

    public static class Constraint {
        public String id;
        /** HARD, DIRECTION or OPEN. */
        public String category;
        public String text;
        public String sourceRequirementId;
    }
}
