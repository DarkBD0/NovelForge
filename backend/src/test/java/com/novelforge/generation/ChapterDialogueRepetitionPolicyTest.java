package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.Version;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChapterDialogueRepetitionPolicyTest {
    private static final String REPLAY="“工装清单怎么整？”\n“按工序排，谁领谁签字。”\n“尺寸链动了，备注不改，现场还按老图做。”";

    @Test void flagsSeveralCopiedDialogueLinesWithoutBlockingConfirmation() {
        Novel novel=new Novel();
        Artifact prior=chapter(1,REPLAY,true); novel.artifacts.add(prior);
        Artifact current=new Artifact(); current.kind=Kind.CHAPTER; current.chapterNumber=2;

        Review review=ChapterDialogueRepetitionPolicy.apply(novel,current,Action.REVIEW,
                new ModelGateway.Generated("第二章","新场景。"+REPLAY,"摘要",List.of(),null),cleanReview());

        assertThat(review.passed()).isTrue();
        assertThat(review.issueDetails()).hasSize(1);
        assertThat(review.issueDetails().get(0).severity()).isEqualTo("作者决定");
        assertThat(review.issueDetails().get(0).evidence()).contains("工装清单怎么整");
    }

    @Test void ignoresOneRecurringPhraseAndUnconfirmedDrafts() {
        Novel novel=new Novel(); novel.artifacts.add(chapter(1,REPLAY,false));
        Artifact current=new Artifact(); current.kind=Kind.CHAPTER; current.chapterNumber=2;
        Review draftOnly=ChapterDialogueRepetitionPolicy.apply(novel,current,Action.REVIEW,
                new ModelGateway.Generated("第二章",REPLAY,"摘要",List.of(),null),cleanReview());
        assertThat(draftOnly.issueDetails()).isEmpty();

        novel.artifacts.clear(); novel.artifacts.add(chapter(1,REPLAY,true));
        Review onePhrase=ChapterDialogueRepetitionPolicy.apply(novel,current,Action.REVIEW,
                new ModelGateway.Generated("第二章","“工装清单怎么整？”还有其他新对白。","摘要",List.of(),null),cleanReview());
        assertThat(onePhrase.issueDetails()).isEmpty();
    }

    private static Artifact chapter(int number,String content,boolean approved) {
        Artifact artifact=new Artifact(); artifact.kind=Kind.CHAPTER; artifact.chapterNumber=number;
        Version version=new Version(); version.title="前章"; version.content=content; version.summary="前章摘要";
        artifact.versions.add(version); if(approved) artifact.approvedVersionId=version.id;
        return artifact;
    }

    private static Review cleanReview() { return new Review(true,List.of(),false,false,false,List.of()); }
}
