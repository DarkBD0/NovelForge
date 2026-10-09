package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds deterministic candidate-claim to verified-history-quote pairs before the model reviews continuity.
 * Pairs are retrieval hints only: neither the claim nor the quote is classified as a conflict here.
 */
@Component
public class HistoricalClaimEvidencePairingService {
    public static final String POLICY_VERSION="historical-claim-evidence-pairing-v1";
    private static final Pattern SENTENCE_BOUNDARY=Pattern.compile("(?<=[。！？!?；;])|\\R+");
    private static final Pattern HIGH_RISK=Pattern.compile(
            "从来没有|从未|此前从未|第一次|首次|并非|没有任何|唯一|一直|只能|明确否认|不是.{0,8}(?:父|母|子|女|亲属|夫妻|恋人)");
    private static final Pattern CHECKABLE=Pattern.compile(
            "父|母|子|女|亲属|夫妻|恋人|死亡|活着|身份|姓名|钥匙|信|获得|发现|来源|"
                    +"位置|位于|属于|持有|时间|时段|值班|班次|白班|夜班|规则|能力");
    private static final Set<String> NOISE_BIGRAMS=Set.of(
            "从未","此前","第一","一次","没有","任何","一直","只能","明确","确认","表示","说道",
            "他的","她的","他们","自己","这个","那个","已经","后来","原来","仍然","并非","不是");

    public record Pair(String id,String candidateClaim,String claimSignal,String historicalQuote,
                       String sourceVersionId,int chapterNumber,int relevanceScore,List<String> sharedTerms) {}
    public record Result(String policyVersion,String authorityState,int candidateClaimCount,int verifiedQuoteCount,
                         List<Pair> pairs,String note) {}
    private record Claim(int index,String text,int priority) {}
    private record Quote(String text,String sourceVersionId,int chapterNumber) {}
    private record Ranked(Claim claim,Quote quote,int score,List<String> sharedTerms) {}

    public Result pair(String candidateText,HistoricalStructuredMemoryShadowService.Aggregate aggregate) {
        return pair(candidateText,quotes(aggregate));
    }

    /** Reads exact sentences from selected confirmed chapter versions; no model extraction is involved. */
    public Result pair(String candidateText,Novel novel,List<Integer> chapterNumbers) {
        if(novel==null||chapterNumbers==null||chapterNumbers.isEmpty()) return pair(candidateText,List.of());
        Set<Integer> requested=Set.copyOf(chapterNumbers);
        List<Quote> source=new ArrayList<>();
        novel.artifacts.stream().filter(item->item.kind==Kind.CHAPTER&&item.clean())
                .filter(item->requested.contains(item.chapterNumber))
                .sorted(Comparator.comparingInt(item->item.chapterNumber))
                .forEach(item->addChapterQuotes(source,item));
        return pair(candidateText,List.copyOf(source));
    }

    /** Makes direct chapter quotes visible to the existing local evidence gate. */
    public HistoricalStructuredMemoryShadowService.Aggregate includePairEvidence(
            HistoricalStructuredMemoryShadowService.Aggregate source,Result result) {
        HistoricalStructuredMemoryShadowService.Aggregate base=source==null
                ?new HistoricalStructuredMemoryShadowService.Aggregate("CONFIRMED_CHAPTER_PAIR_EVIDENCE",
                List.of(),List.of(),List.of(),"没有模型提取记忆") : source;
        List<HistoricalStructuredMemoryShadowService.ShadowFact> facts=new ArrayList<>(base.facts());
        LinkedHashSet<String> seen=new LinkedHashSet<>();
        facts.forEach(item->item.evidence().forEach(value->seen.add(value.sourceVersionId()+"\n"+value.quote())));
        int index=0;
        if(result!=null) for(Pair pair:result.pairs()) {
            String key=pair.sourceVersionId()+"\n"+pair.historicalQuote();
            if(!seen.add(key)) continue;
            var evidence=new HistoricalStructuredMemoryShadowService.ShadowEvidence(pair.historicalQuote(),
                    pair.sourceVersionId(),pair.chapterNumber());
            facts.add(new HistoricalStructuredMemoryShadowService.ShadowFact("direct_pair_evidence_"+(++index),
                    "EVENT","本地按字面相关性选中的已确认章节原句","ACTIVE",pair.chapterNumber(),
                    pair.chapterNumber(),List.of(pair.sourceVersionId()),List.of(evidence)));
        }
        return new HistoricalStructuredMemoryShadowService.Aggregate(base.authorityState(),List.copyOf(facts),
                base.entities(),base.relations(),base.note()+"；direct_pair_evidence 来自已确认章节原文，不依赖模型提取");
    }

    private Result pair(String candidateText,List<Quote> quotes) {
        List<Claim> claims=claims(candidateText);
        List<Ranked> ranked=new ArrayList<>();
        for(Claim claim:claims) {
            for(Quote quote:quotes) {
                List<String> shared=sharedTerms(claim.text(),quote.text());
                // One common bigram is often just a character name or generic verb and is too weak to form a pair.
                if(shared.size()<2) continue;
                int score=shared.size()*100+claim.priority();
                if(normalize(claim.text()).contains(normalize(quote.text()))
                        ||normalize(quote.text()).contains(normalize(claim.text()))) score+=500;
                ranked.add(new Ranked(claim,quote,score,shared));
            }
        }
        ranked.sort(Comparator.comparingInt(Ranked::score).reversed()
                .thenComparingInt(item->item.claim().index())
                .thenComparing((Ranked item)->item.quote().chapterNumber(),Comparator.reverseOrder())
                .thenComparing(item->item.quote().text()));

        LinkedHashMap<Integer,Integer> perClaim=new LinkedHashMap<>();
        List<Pair> selected=new ArrayList<>();
        LinkedHashSet<String> seen=new LinkedHashSet<>();
        for(Ranked item:ranked) {
            if(selected.size()>=12) break;
            int perClaimCount=perClaim.getOrDefault(item.claim().index(),0);
            if(perClaimCount>=3) continue;
            String key=item.claim().index()+"\n"+item.quote().sourceVersionId()+"\n"+item.quote().text();
            if(!seen.add(key)) continue;
            String signal=HIGH_RISK.matcher(item.claim().text()).find()?"EXCLUSIVE_CLAIM":"CHECKABLE_CLAIM";
            selected.add(new Pair("pair_"+(selected.size()+1),item.claim().text(),signal,item.quote().text(),
                    item.quote().sourceVersionId(),item.quote().chapterNumber(),item.score(),item.sharedTerms()));
            perClaim.put(item.claim().index(),perClaimCount+1);
        }
        return new Result(POLICY_VERSION,"DETERMINISTIC_RETRIEVAL_HINT_ONLY",claims.size(),quotes.size(),
                List.copyOf(selected),"配对只用于提示核对范围，不代表存在冲突；historicalQuote 来自本地逐字验证的已确认章节正文");
    }

    private void addChapterQuotes(List<Quote> target,Artifact artifact) {
        var version=artifact.approved();
        if(version==null||version.content==null||version.content.isBlank()) return;
        for(String raw:SENTENCE_BOUNDARY.split(version.content)) {
            String value=raw==null?"":raw.strip();
            if(value.length()<4) continue;
            if(value.length()>800) value=value.substring(0,800);
            target.add(new Quote(value,version.id,artifact.chapterNumber));
        }
    }

    private List<Claim> claims(String text) {
        if(text==null||text.isBlank()) return List.of();
        List<Claim> result=new ArrayList<>();
        int index=0;
        for(String raw:SENTENCE_BOUNDARY.split(text)) {
            String value=raw==null?"":raw.strip();
            if(value.isBlank()) continue;
            if(value.length()>600) value=value.substring(0,600);
            boolean highRisk=HIGH_RISK.matcher(value).find();
            if(!highRisk&&!CHECKABLE.matcher(value).find()) continue;
            result.add(new Claim(++index,value,highRisk?1000:100));
            if(result.size()>=16) break;
        }
        return result.stream().sorted(Comparator.comparingInt(Claim::priority).reversed()
                .thenComparingInt(Claim::index)).toList();
    }

    private List<Quote> quotes(HistoricalStructuredMemoryShadowService.Aggregate aggregate) {
        if(aggregate==null) return List.of();
        LinkedHashMap<String,Quote> result=new LinkedHashMap<>();
        aggregate.facts().forEach(item->item.evidence().forEach(value->addQuote(result,value)));
        aggregate.entities().forEach(item->item.evidence().forEach(value->addQuote(result,value)));
        aggregate.relations().forEach(item->item.evidence().forEach(value->addQuote(result,value)));
        return List.copyOf(result.values());
    }

    private void addQuote(LinkedHashMap<String,Quote> target,
            HistoricalStructuredMemoryShadowService.ShadowEvidence evidence) {
        if(evidence==null||evidence.quote()==null||evidence.quote().isBlank()) return;
        String key=evidence.sourceVersionId()+"\n"+evidence.quote();
        target.putIfAbsent(key,new Quote(evidence.quote(),evidence.sourceVersionId(),evidence.chapterNumber()));
    }

    private List<String> sharedTerms(String left,String right) {
        String normalizedLeft=normalize(left); String normalizedRight=normalize(right);
        if(normalizedLeft.length()<2||normalizedRight.length()<2) return List.of();
        LinkedHashSet<String> leftTerms=bigrams(normalizedLeft);
        LinkedHashSet<String> result=new LinkedHashSet<>();
        for(String term:bigrams(normalizedRight)) {
            if(leftTerms.contains(term)&&!NOISE_BIGRAMS.contains(term)) result.add(term);
            if(result.size()>=8) break;
        }
        return List.copyOf(result);
    }

    private LinkedHashSet<String> bigrams(String value) {
        LinkedHashSet<String> result=new LinkedHashSet<>();
        for(int index=0;index<value.length()-1;index++) result.add(value.substring(index,index+2));
        return result;
    }

    private String normalize(String value) {
        return value==null?"":value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。！？；：、,.!?;:'\\\"“”‘’（）()\\[\\]【】_-]+","");
    }
}
