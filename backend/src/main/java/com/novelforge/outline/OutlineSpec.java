package com.novelforge.outline;

import java.util.ArrayList;
import java.util.List;

/** Machine-readable design contract for one outline version. */
public class OutlineSpec {
    public String schemaVersion="1";
    public String storyCore;
    public String genreTone;
    public String protagonistGoal;
    public String coreConflict;
    public String endingContract;
    public List<RequirementTrace> requirements=new ArrayList<>();
    public List<WorldRule> worldRules=new ArrayList<>();
    public List<CharacterArc> characters=new ArrayList<>();
    public List<StoryPart> parts=new ArrayList<>();
    public List<Foreshadow> foreshadows=new ArrayList<>();
    public List<StoryThread> threads=new ArrayList<>();
    public List<Constraint> constraints=new ArrayList<>();
    public List<String> creativeAssumptions=new ArrayList<>();

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
