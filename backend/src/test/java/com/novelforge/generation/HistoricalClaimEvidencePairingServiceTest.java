package com.novelforge.generation;

import com.novelforge.novel.Novel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalClaimEvidencePairingServiceTest {
    private final HistoricalClaimEvidencePairingService service=new HistoricalClaimEvidencePairingService();

    @Test void pairsExclusiveObjectClaimWithMostRelevantVerifiedQuote() {
        var keyQuote=new HistoricalStructuredMemoryShadowService.ShadowEvidence(
                "父亲来信中的铜钥匙压在那行灯语下面。","chapter-2",2);
        var unrelated=new HistoricalStructuredMemoryShadowService.ShadowEvidence(
                "码头的雾在午夜重新升起。","chapter-3",3);
        var aggregate=new HistoricalStructuredMemoryShadowService.Aggregate("MODEL_DERIVED_HISTORICAL_SHADOW",
                List.of(
                        new HistoricalStructuredMemoryShadowService.ShadowFact("copper_key","EVENT","来信里有铜钥匙",
                                "ACTIVE",2,2,List.of("chapter-2"),List.of(keyQuote)),
                        new HistoricalStructuredMemoryShadowService.ShadowFact("fog","WORLD","午夜起雾",
                                "ACTIVE",3,3,List.of("chapter-3"),List.of(unrelated))),
                List.of(),List.of(),"只读影子");

        var result=service.pair("林舟确认，父亲来信里从来没有铜钥匙。他第一次拿到铜钥匙是在旧船舱。",aggregate);

        assertThat(result.authorityState()).isEqualTo("DETERMINISTIC_RETRIEVAL_HINT_ONLY");
        assertThat(result.candidateClaimCount()).isEqualTo(2);
        assertThat(result.verifiedQuoteCount()).isEqualTo(2);
        assertThat(result.pairs()).isNotEmpty();
        assertThat(result.pairs().getFirst().candidateClaim()).contains("从来没有铜钥匙");
        assertThat(result.pairs().getFirst().historicalQuote()).isEqualTo(keyQuote.quote());
        assertThat(result.pairs().getFirst().claimSignal()).isEqualTo("EXCLUSIVE_CLAIM");
        assertThat(result.pairs().getFirst().sharedTerms()).contains("铜钥","钥匙");
        assertThat(result.pairs()).noneMatch(item->item.historicalQuote().equals(unrelated.quote()));
    }

    @Test void doesNotInventPairsWithoutSharedSubjectTerms() {
        var evidence=new HistoricalStructuredMemoryShadowService.ShadowEvidence(
                "码头的雾在午夜重新升起。","chapter-3",3);
        var aggregate=new HistoricalStructuredMemoryShadowService.Aggregate("MODEL_DERIVED_HISTORICAL_SHADOW",
                List.of(new HistoricalStructuredMemoryShadowService.ShadowFact("fog","WORLD","午夜起雾",
                        "ACTIVE",3,3,List.of("chapter-3"),List.of(evidence))),List.of(),List.of(),"只读影子");

        var result=service.pair("林舟第一次拿到铜钥匙。",aggregate);

        assertThat(result.pairs()).isEmpty();
    }

    @Test void readsEvidenceDirectlyFromConfirmedChapterWhenModelMemoryMissesIt() {
        Novel novel=new Novel();
        Novel.Artifact chapter=new Novel.Artifact(); chapter.kind=Novel.Kind.CHAPTER; chapter.chapterNumber=1;
        Novel.Version version=new Novel.Version(); version.title="第一章";
        version.content="林舟推开修船铺的门。父亲来信里只有一枚铜钥匙和一行灯语。";
        chapter.versions.add(version); chapter.approvedVersionId=version.id; novel.artifacts.add(chapter);

        var result=service.pair("林舟确认，父亲来信里从来没有铜钥匙。",novel,List.of(1));
        var empty=new HistoricalStructuredMemoryShadowService.Aggregate("MODEL_DERIVED_HISTORICAL_SHADOW",
                List.of(),List.of(),List.of(),"模型未提取到目标事实");
        var augmented=service.includePairEvidence(empty,result);

        assertThat(result.pairs()).hasSize(1);
        assertThat(result.pairs().getFirst().historicalQuote()).contains("父亲来信里只有一枚铜钥匙");
        assertThat(augmented.facts()).hasSize(1);
        assertThat(augmented.facts().getFirst().evidence().getFirst().sourceVersionId()).isEqualTo(version.id);
    }
}
