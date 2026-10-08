package com.novelforge.canon;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class CanonService {
    private final FormalMemoryStore formalMemory;
    public CanonService() { this.formalMemory=null; }
    @Autowired public CanonService(ObjectProvider<FormalMemoryStore> formalMemory) { this.formalMemory=formalMemory.getIfAvailable(); }
    public record Entry(Fact fact, String sourceArtifactId, String sourceVersionId, int chapter) {}
    public List<Entry> at(Novel n, int beforeChapter) {
        if(formalMemory!=null) {
            var stored=formalMemory.at(n,beforeChapter);
            if(stored.isPresent()) return stored.get().stream()
                    .map(item->new Entry(item.fact(),item.sourceArtifactId(),item.sourceVersionId(),item.chapter())).toList();
        }
        var entries = new LinkedHashMap<String, Entry>();
        // Rebuild from confirmed versions: approval and canon can never partially commit.
        for (Artifact a : n.artifacts) {
            if (a.approvedVersionId==null || a.approved()==null || a.needsRevision || a.kind == Kind.PLAN || a.kind == Kind.OUTLINE) continue;
            if (a.kind == Kind.CHAPTER && a.chapterNumber >= beforeChapter) continue;
            for (Fact fact : a.approved().facts) entries.put(fact.type() + ":" + fact.key(), new Entry(fact, a.id, a.approvedVersionId, a.chapterNumber));
        }
        return new ArrayList<>(entries.values());
    }
    public FormalMemoryStore.StructuredMemory structured(Novel novel) {
        if(formalMemory==null) return new FormalMemoryStore.StructuredMemory(List.of(),List.of());
        return formalMemory.structured(novel.id)
                .orElseGet(()->new FormalMemoryStore.StructuredMemory(List.of(),List.of()));
    }
}
