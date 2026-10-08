package com.novelforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.revision.ImpactAnalyzer;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ImpactAnalyzerTest {
    private final ImpactAnalyzer impact=new ImpactAnalyzer(new ObjectMapper());
    private Artifact artifact(Kind kind,String...sources) {
        Artifact a=new Artifact();a.kind=kind;Version v=new Version();v.title="标题";v.content="内容";v.summary="摘要";
        v.sourceVersionIds=List.of(sources);a.versions.add(v);a.approvedVersionId=v.id;return a;
    }
    @Test void invalidatesOnlyDirectAndTransitiveDependenciesWhenMetadataExists() {
        Novel n=new Novel();Artifact outline=artifact(Kind.OUTLINE);Artifact characters=artifact(Kind.CHARACTERS,outline.latest().id);
        Artifact independent=artifact(Kind.PLAN,"unrelated-version");Artifact chapter=artifact(Kind.CHAPTER,characters.latest().id);
        n.artifacts.addAll(List.of(outline,characters,independent,chapter));
        var preview=impact.previewFollowing(n,outline);
        assertThat(preview).containsExactly(characters.id,chapter.id);
        assertThat(n.artifacts).containsExactly(outline,characters,independent,chapter);
        assertThat(n.artifacts).noneMatch(item->item.needsRevision);
        var change=impact.invalidateFollowing(n,outline,"只改主角动机");
        assertThat(change.affectedArtifactIds).containsExactly(characters.id,chapter.id);
        assertThat(independent.needsRevision).isFalse();
        assertThat(impact.changed(outline.latest(),"标题","内容","摘要",List.of(),null)).isFalse();
        assertThat(impact.changed(outline.latest(),"标题","修改后的内容","摘要",List.of(),null)).isTrue();
    }
}
