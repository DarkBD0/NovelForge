package com.novelforge.revision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Component
public class ImpactAnalyzer {
    private final ObjectMapper mapper;
    public ImpactAnalyzer(ObjectMapper mapper) { this.mapper=mapper; }

    public boolean changed(Version before,String title,String content,String summary,java.util.List<Fact> facts,Plan plan) {
        return !Objects.equals(before.title,title) || !Objects.equals(before.content,content) || !Objects.equals(before.summary,summary)
                || !Objects.equals(before.facts,facts) || !mapper.valueToTree(before.plan).equals(mapper.valueToTree(plan));
    }
    /** Maintain a repair order. Future plans must adapt to changed actual events, never the reverse. */
    public Change invalidateFollowing(Novel n, Artifact source, String reason) {
        Change change = new Change(); change.sourceArtifactId = source.id; change.reason = reason;
        Set<String> forcedArtifacts=new HashSet<>();
        if (source.kind == Kind.CHAPTER) {
            int originalIndex = n.artifacts.indexOf(source);
            var earlyFuturePlans = n.artifacts.stream().filter(a -> a.kind == Kind.PLAN
                    && n.artifacts.indexOf(a) < originalIndex && a.latest().plan.startChapter > source.chapterNumber).toList();
            earlyFuturePlans.forEach(a->forcedArtifacts.add(a.id));
            // An early plan assumed this chapter's events. Review the changed chapter first, then replan
            // against the now-confirmed actual event. Earlier actual chapters remain authoritative.
            n.artifacts.removeAll(earlyFuturePlans);
            n.artifacts.addAll(n.artifacts.indexOf(source) + 1, earlyFuturePlans);
        }
        int start = n.artifacts.indexOf(source);
        Set<String> affectedVersions=new HashSet<>();
        source.versions.forEach(v->affectedVersions.add(v.id));
        for (int i = start + 1; i < n.artifacts.size(); i++) {
            Artifact a = n.artifacts.get(i);
            Version basis=a.approved()!=null?a.approved():a.latest();
            boolean legacy=basis==null || basis.sourceVersionIds==null || basis.sourceVersionIds.isEmpty();
            boolean depends=forcedArtifacts.contains(a.id) || legacy || basis.sourceVersionIds.stream().anyMatch(affectedVersions::contains);
            if (depends) {
                a.needsRevision = true; change.affectedArtifactIds.add(a.id);
                a.versions.forEach(v->affectedVersions.add(v.id));
            }
        }
        n.changes.add(change); n.status = "WRITING";
        return change;
    }
}
