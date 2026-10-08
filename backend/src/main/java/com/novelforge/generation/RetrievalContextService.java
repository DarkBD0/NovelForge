package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.projection.ElasticsearchProjectionClient;
import com.novelforge.projection.ElasticsearchShadowSearchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Optional, read-only retrieval aid for long-running chapter work. MySQL-approved
 * versions remain authoritative; Elasticsearch hits are accepted only after they
 * can be traced back to the aggregate's current approved version.
 */
@Service
public class RetrievalContextService {
    public record Result(String status,String runId,String query,List<Map<String,Object>> hits,
                         List<String> sourceVersionIds,String note) {}

    private final ElasticsearchShadowSearchService searches;
    private final boolean enabled;
    private final int resultLimit;
    private final int maxChars;

    public RetrievalContextService(ElasticsearchShadowSearchService searches,
                                   @Value("${novelforge.retrieval.context-enabled:false}") boolean enabled,
                                   @Value("${novelforge.retrieval.result-limit:5}") int resultLimit,
                                   @Value("${novelforge.retrieval.max-context-chars:6000}") int maxChars) {
        this.searches=searches;
        this.enabled=enabled;
        this.resultLimit=Math.max(1,Math.min(10,resultLimit));
        this.maxChars=Math.max(500,Math.min(20000,maxChars));
    }

    public Result retrieve(Novel novel,int beforeChapter,String query) {
        if(!enabled) return null;
        String normalized=trim(query,800);
        if(normalized.isBlank()) return new Result("SKIPPED","",normalized,List.of(),List.of(),"当前任务没有可用于检索的章节主题");
        try {
            ElasticsearchShadowSearchService.Run run=searches.search(novel.id,normalized,beforeChapter,resultLimit);
            Map<String,Artifact> approved=new LinkedHashMap<>();
            for(Artifact artifact:novel.artifacts) {
                if(artifact.approvedVersionId==null || !List.of(Kind.CHARACTERS,Kind.CHAPTER).contains(artifact.kind)) continue;
                if(artifact.kind==Kind.CHAPTER && artifact.chapterNumber>=beforeChapter) continue;
                approved.put(artifact.id,artifact);
            }
            List<Map<String,Object>> accepted=new ArrayList<>();
            Set<String> sourceVersions=new LinkedHashSet<>();
            int usedChars=0;
            for(ElasticsearchProjectionClient.Hit hit:run.hits()) {
                Artifact artifact=approved.get(hit.artifactId());
                if(artifact==null || !artifact.approvedVersionId.equals(hit.sourceVersionId())) continue;
                String summary=trim(hit.summary(),900);
                String excerpt=trim(hit.excerpt(),500);
                int itemChars=summary.length()+excerpt.length()+hit.title().length();
                if(!accepted.isEmpty() && usedChars+itemChars>maxChars) break;
                Map<String,Object> item=new LinkedHashMap<>();
                item.put("artifactId",hit.artifactId()); item.put("sourceVersionId",hit.sourceVersionId());
                item.put("chapterNumber",hit.chapterNumber()); item.put("title",hit.title());
                item.put("summary",summary); item.put("excerpt",excerpt); item.put("score",hit.score());
                item.put("authorityState","CONFIRMED_SOURCE_VERIFIED");
                accepted.add(item); sourceVersions.add(hit.sourceVersionId()); usedChars+=itemChars;
            }
            String status=accepted.isEmpty()?"EMPTY":"APPLIED";
            String note=accepted.isEmpty()?"没有通过当前 MySQL 已确认版本复核的命中，继续使用正式记忆"
                    :"仅作为相关历史定位；若与正式档案冲突，以正式档案和已确认正文为准";
            return new Result(status,run.id(),normalized,List.copyOf(accepted),List.copyOf(sourceVersions),note);
        } catch(RuntimeException failure) {
            return new Result("FALLBACK","",normalized,List.of(),List.of(),"辅助检索不可用，已回退到 MySQL 正式记忆和已确认章节摘要");
        }
    }

    public boolean enabled() { return enabled; }

    private static String trim(String value,int limit) {
        if(value==null) return "";
        String normalized=value.strip();
        return normalized.length()<=limit?normalized:normalized.substring(0,limit);
    }
}
