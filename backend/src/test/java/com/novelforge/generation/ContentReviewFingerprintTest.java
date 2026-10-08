package com.novelforge.generation;

import com.novelforge.novel.Novel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContentReviewFingerprintTest {
    private final ContentReviewFingerprint fingerprints=new ContentReviewFingerprint();

    @Test void ignoresExtractedStateButChangesForVisibleTextOrAuthoritativeInputs() {
        Novel novel=new Novel(); novel.title="零点回声"; novel.synopsis="雨夜谜案"; novel.requirements="克制";
        Novel.Artifact chapter=new Novel.Artifact(); chapter.kind=Novel.Kind.CHAPTER; chapter.chapterNumber=30;
        novel.artifacts.add(chapter);
        var first=new ModelGateway.Generated("第三十章","正文","摘要",
                List.of(new Novel.Fact("a","EVENT","复合档案","ACTIVE")),null);
        var stateOnly=new ModelGateway.Generated("第三十章","正文","摘要",
                List.of(new Novel.Fact("a","EVENT","已经拆分的档案","ACTIVE")),null);

        assertThat(fingerprints.of(novel,chapter,first,"检查"))
                .isEqualTo(fingerprints.of(novel,chapter,stateOnly,"检查"));
        assertThat(fingerprints.of(novel,chapter,first,"检查"))
                .isNotEqualTo(fingerprints.of(novel,chapter,
                        new ModelGateway.Generated("第三十章","改过的正文","摘要",List.of(),null),"检查"));

        Novel.Artifact outline=new Novel.Artifact(); outline.kind=Novel.Kind.OUTLINE; outline.approvedVersionId="outline-v2";
        novel.artifacts.add(outline);
        assertThat(fingerprints.of(novel,chapter,first,"检查"))
                .isNotEqualTo(fingerprints.of(new Novel(),null,first,"检查"));
    }
}
