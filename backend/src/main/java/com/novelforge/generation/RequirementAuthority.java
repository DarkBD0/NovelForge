package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Version;
import com.novelforge.outline.OutlineSpec.RequirementTrace;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Resolves the one requirement snapshot that is authoritative for model calls.
 *
 * <p>The project input is the initial snapshot. Once an outline is confirmed, its
 * explicit requirement trace becomes the new snapshot and the requirement trace
 * in older outline versions is historical only. This keeps provenance without
 * asking a model to reconcile contradictory old and new prose on every call.</p>
 */
@Component
public class RequirementAuthority {
    public record Entry(String id,String text,String source) {}
    public record Snapshot(String text,String authority,String sourceVersionId,List<Entry> entries) {}

    public Snapshot resolve(Novel novel) {
        Version approved=approvedOutline(novel);
        if(approved!=null && approved.outlineSpec!=null && approved.outlineSpec.requirements!=null) {
            LinkedHashSet<String> seen=new LinkedHashSet<>();
            List<Entry> entries=new ArrayList<>();
            for(RequirementTrace trace:approved.outlineSpec.requirements) {
                if(trace==null || !"EXPLICIT".equalsIgnoreCase(value(trace.type))) continue;
                String text=value(trace.text).strip();
                if(text.isBlank() || !seen.add(normalize(text))) continue;
                entries.add(new Entry(value(trace.id),text,value(trace.source)));
            }
            if(!entries.isEmpty()) return new Snapshot(join(entries),"APPROVED_OUTLINE_SNAPSHOT",approved.id,List.copyOf(entries));
        }
        String initial=value(novel.requirements).strip();
        List<Entry> entries=initial.isBlank()?List.of():List.of(new Entry("project_input",initial,"requirements"));
        return new Snapshot(initial,"PROJECT_INPUT",null,entries);
    }

    private Version approvedOutline(Novel novel) {
        for(int index=novel.artifacts.size()-1;index>=0;index--) {
            Artifact artifact=novel.artifacts.get(index);
            if(artifact.kind==Kind.OUTLINE && artifact.approvedVersionId!=null) {
                Version approved=artifact.approved();
                if(approved!=null) return approved;
            }
        }
        return null;
    }

    private String join(List<Entry> entries) {
        StringBuilder result=new StringBuilder();
        for(Entry entry:entries) {
            if(!result.isEmpty()) result.append('\n');
            result.append("- ").append(entry.text);
        }
        return result.toString();
    }
    private String normalize(String text) { return text.replaceAll("[\\s，。；、！？]","").toLowerCase(); }
    private String value(String text) { return text==null?"":text; }
}
