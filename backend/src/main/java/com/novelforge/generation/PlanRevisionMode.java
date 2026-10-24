package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Change;
import com.novelforge.novel.Novel.Kind;

/** Distinguishes upstream story redesign from calibration after a written chapter. */
public final class PlanRevisionMode {
    public enum Mode { NEW_BATCH, HANDOFF_CALIBRATION, UPSTREAM_REPLAN }

    private PlanRevisionMode() {}

    public static Mode resolve(Novel novel,Action action,Artifact target) {
        if(action==Action.PLAN) return Mode.NEW_BATCH;
        if(target==null || target.kind!=Kind.PLAN || target.clean())
            return Mode.HANDOFF_CALIBRATION;
        for(int index=novel.changes.size()-1;index>=0;index--) {
            Change change=novel.changes.get(index);
            if(change.affectedArtifactIds==null || !change.affectedArtifactIds.contains(target.id)) continue;
            Artifact source=novel.artifacts.stream().filter(item->item.id.equals(change.sourceArtifactId))
                    .findFirst().orElse(null);
            if(source==null) continue;
            if(source.kind==Kind.OUTLINE || source.kind==Kind.CHARACTERS) return Mode.UPSTREAM_REPLAN;
            if(source.kind==Kind.CHAPTER) return Mode.HANDOFF_CALIBRATION;
        }
        return Mode.HANDOFF_CALIBRATION;
    }
}
