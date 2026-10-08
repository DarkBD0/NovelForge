package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Version;
import com.novelforge.projection.ElasticsearchProjectionClient;
import com.novelforge.projection.ElasticsearchShadowSearchService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalContextServiceTest {
    @Test void acceptsOnlyCurrentApprovedSourcesBeforeTheChapterBoundary() {
        Novel novel=new Novel();
        Artifact earlier=chapter(2,"已确认旧版本");
        Version unconfirmed=new Version(); unconfirmed.title="未确认修订"; unconfirmed.content="不能进入检索上下文";
        earlier.versions.add(unconfirmed);
        Artifact future=chapter(8,"未来章节");
        novel.artifacts.addAll(List.of(earlier,future));

        var valid=hit(earlier.approvedVersionId,earlier.id,2,"有效片段");
        var stale=hit(unconfirmed.id,earlier.id,2,"过期片段");
        var futureHit=hit(future.approvedVersionId,future.id,8,"未来片段");
        ElasticsearchShadowSearchService searches=mock(ElasticsearchShadowSearchService.class);
        when(searches.search(novel.id,"当前章节主题",5,5)).thenReturn(run(novel.id,List.of(valid,stale,futureHit)));

        RetrievalContextService.Result result=new RetrievalContextService(searches,true,5,6000)
                .retrieve(novel,5,"当前章节主题");

        assertThat(result.status()).isEqualTo("APPLIED");
        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().getFirst()).containsEntry("sourceVersionId",earlier.approvedVersionId)
                .containsEntry("authorityState","CONFIRMED_SOURCE_VERIFIED");
        assertThat(result.sourceVersionIds()).containsExactly(earlier.approvedVersionId);
    }

    @Test void fallsBackWithoutBlockingWhenElasticsearchFails() {
        Novel novel=new Novel();
        ElasticsearchShadowSearchService searches=mock(ElasticsearchShadowSearchService.class);
        when(searches.search(anyString(),anyString(),anyInt(),anyInt())).thenThrow(new IllegalStateException("offline"));

        RetrievalContextService.Result result=new RetrievalContextService(searches,true,5,6000)
                .retrieve(novel,3,"线索");

        assertThat(result.status()).isEqualTo("FALLBACK");
        assertThat(result.hits()).isEmpty();
        assertThat(result.note()).contains("已回退");
    }

    @Test void staysCompletelyOutOfTheContextWhenDisabled() {
        RetrievalContextService service=new RetrievalContextService(mock(ElasticsearchShadowSearchService.class),false,5,6000);
        assertThat(service.retrieve(new Novel(),2,"线索")).isNull();
    }

    private Artifact chapter(int number,String content) {
        Artifact artifact=new Artifact(); artifact.kind=Kind.CHAPTER; artifact.chapterNumber=number;
        Version version=new Version(); version.title="第"+number+"章"; version.content=content; version.summary=content;
        artifact.versions.add(version); artifact.approvedVersionId=version.id; return artifact;
    }

    private ElasticsearchProjectionClient.Hit hit(String versionId,String artifactId,int chapter,String text) {
        return new ElasticsearchProjectionClient.Hit(versionId,artifactId,chapter,10,"标题",text,text);
    }

    private ElasticsearchShadowSearchService.Run run(String novelId,List<ElasticsearchProjectionClient.Hit> hits) {
        return new ElasticsearchShadowSearchService.Run("run-1",novelId,"当前章节主题",5,5,"SUCCEEDED",hits.size(),10,
                "2026-10-02T00:00:00Z",null,hits);
    }
}
