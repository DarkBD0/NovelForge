package com.novelforge.revision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
public class ImpactAnalyzer {
    private record ImpactPlan(List<Artifact> order,List<String> affectedArtifactIds) {}
    private final ObjectMapper mapper;
    public ImpactAnalyzer(ObjectMapper mapper) { this.mapper=mapper; }

    public boolean changed(Version before,String title,String content,String summary,java.util.List<Fact> facts,Plan plan) {
        return !Objects.equals(before.title,title) || !Objects.equals(before.content,content) || !Objects.equals(before.summary,summary)
                || !Objects.equals(before.facts,facts) || !mapper.valueToTree(before.plan).equals(mapper.valueToTree(plan));
    }
    /** Maintain a repair order. Future plans must adapt to changed actual events, never the reverse. */
    public Change invalidateFollowing(Novel n, Artifact source, String reason) {
        Change change = new Change(); change.sourceArtifactId = source.id; change.reason = reason;
        ImpactPlan plan=impactPlan(n,source);
        n.artifacts.clear(); n.artifacts.addAll(plan.order);
        for(String artifactId:plan.affectedArtifactIds) {
            Artifact artifact=n.artifacts.stream().filter(item->item.id.equals(artifactId)).findFirst().orElseThrow();
            artifact.needsRevision=true; change.affectedArtifactIds.add(artifactId);
        }
        n.changes.add(change); n.status = "WRITING";
        return change;
    }

    /** Read-only prediction used by shadow evaluation; it never changes order or revision flags. */
    public List<String> previewFollowing(Novel n,Artifact source) {
        return impactPlan(n,source).affectedArtifactIds;
    }

    private ImpactPlan impactPlan(Novel n,Artifact source) {
        List<Artifact> order=new ArrayList<>(n.artifacts);
        if(!order.contains(source)) throw new IllegalArgumentException("源内容单元不属于当前小说");
        Set<String> forcedArtifacts=new HashSet<>();
        if (source.kind == Kind.CHAPTER) {
            int originalIndex = order.indexOf(source);
            var earlyFuturePlans = order.stream().filter(a -> a.kind == Kind.PLAN && order.indexOf(a) < originalIndex
                    && a.latest()!=null && a.latest().plan!=null && a.latest().plan.startChapter > source.chapterNumber).toList();
            earlyFuturePlans.forEach(a->forcedArtifacts.add(a.id));
            order.removeAll(earlyFuturePlans);
            order.addAll(order.indexOf(source)+1,earlyFuturePlans);
        }
        int start = order.indexOf(source);
        Set<String> affectedVersions=new HashSet<>();
        source.versions.forEach(v->affectedVersions.add(v.id));
        List<String> affectedArtifacts=new ArrayList<>();
        for (int i = start + 1; i < order.size(); i++) {
            Artifact a = order.get(i);
            Version basis=a.approved()!=null?a.approved():a.latest();
            boolean legacy=basis==null || basis.sourceVersionIds==null || basis.sourceVersionIds.isEmpty();
            boolean depends=forcedArtifacts.contains(a.id) || legacy || basis.sourceVersionIds.stream().anyMatch(affectedVersions::contains);
            if (depends) {
                affectedArtifacts.add(a.id);
                a.versions.forEach(v->affectedVersions.add(v.id));
            }
        }
        return new ImpactPlan(List.copyOf(order),List.copyOf(affectedArtifacts));
    }
}
