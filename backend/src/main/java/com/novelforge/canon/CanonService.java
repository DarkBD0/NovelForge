package com.novelforge.canon;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class CanonService {
    public record Entry(Fact fact, String sourceArtifactId, String sourceVersionId, int chapter) {}
    public List<Entry> at(Novel n, int beforeChapter) {
        var entries = new LinkedHashMap<String, Entry>();
        // Rebuild from confirmed versions: approval and canon can never partially commit.
        for (Artifact a : n.artifacts) {
            if (!a.clean() || a.kind == Kind.PLAN || a.kind == Kind.OUTLINE) continue;
            if (a.kind == Kind.CHAPTER && a.chapterNumber >= beforeChapter) continue;
            for (Fact fact : a.approved().facts) entries.put(fact.type() + ":" + fact.key(), new Entry(fact, a.id, a.approvedVersionId, a.chapterNumber));
        }
        return new ArrayList<>(entries.values());
    }
}
