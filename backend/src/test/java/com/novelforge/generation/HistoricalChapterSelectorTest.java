package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Version;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalChapterSelectorTest {
    private final HistoricalChapterSelector selector=new HistoricalChapterSelector();

    @Test void excludesOrdinaryRecentWindowAndSamplesHistoryEvenly() {
        Novel novel=novelWithConfirmedChapters(12);
        assertThat(selector.select(novel,13,4)).containsExactly(1,4,6,9);
    }

    @Test void returnsNoSampleBeforeThereIsLongRangeHistory() {
        assertThat(selector.select(novelWithConfirmedChapters(3),4,4)).isEmpty();
        assertThat(selector.select(novelWithConfirmedChapters(4),5,4)).containsExactly(1);
    }

    private Novel novelWithConfirmedChapters(int count) {
        Novel novel=new Novel();
        for(int number=1;number<=count;number++) {
            Artifact artifact=new Artifact(); artifact.kind=Kind.CHAPTER; artifact.chapterNumber=number;
            Version version=new Version(); version.title="第"+number+"章"; version.content="正文"+number;
            artifact.versions.add(version); artifact.approvedVersionId=version.id; novel.artifacts.add(artifact);
        }
        return novel;
    }
}
