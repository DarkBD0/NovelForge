package com.novelforge.generation;

import com.novelforge.novel.Novel.Fact;
import com.novelforge.novel.Novel.StateEntity;
import com.novelforge.novel.Novel.StateEvidence;
import com.novelforge.novel.Novel.StateRelation;
import com.novelforge.shared.Problem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.novelforge.novel.Novel.Version;

import static com.novelforge.shared.Problem.require;

/** Deterministic guard for model-proposed chapter state deltas. */
@Component
public class StateExtractionPolicy {
    public static final String VERSION="state-extraction-v10";
    private static final int MAX_EVIDENCE_LENGTH=2000;
    private static final int MAX_EVIDENCE_QUOTES=10;
    private static final Set<String> TYPES=Set.of("WORLD","CHARACTER","TIMELINE","EVENT","FORESHADOW");
    private static final Set<String> STATES=Set.of("ACTIVE","OPEN","RESOLVED");
    private static final Set<String> ENTITY_TYPES=Set.of("CHARACTER","LOCATION","ORGANIZATION","ITEM","CONCEPT");
    private static final Set<String> RELATION_TYPES=Set.of("FAMILY","FRIEND","ALLY","ENEMY","KNOWS","LOVES","COLLEAGUE",
            "WORKS_FOR","MEMBER_OF","OWNS","USES","LOCATED_AT","CONTROLS","CREATED","PROTECTS",
            "INVESTIGATES","DELEGATES_TO","CUSTODIAN_OF","RELATED_TO");
    private static final Set<String> RELATION_STATES=Set.of("ACTIVE","ENDED");
    private static final java.util.regex.Pattern MACHINE_KEY=java.util.regex.Pattern.compile("[a-z][a-z0-9_]{1,199}");
    public record ValidatedState(List<Fact> facts,List<StateEntity> entities,List<StateRelation> relations,
                                 List<StateEvidence> evidence) {}

    public List<Fact> validate(ModelGateway.Generated candidate,ModelGateway.StateExtraction extraction) {
        return validateAll(candidate,extraction).facts();
    }

    public ValidatedState validateAll(ModelGateway.Generated candidate,ModelGateway.StateExtraction extraction) {
        return validateAll(candidate,extraction,false);
    }

    /** Historical shadow reconstruction keeps individually valid entries when sibling entries are malformed. */
    public ValidatedState validateAllBestEffort(ModelGateway.Generated candidate,
            ModelGateway.StateExtraction extraction) {
        return validateAll(candidate,extraction,true);
    }

    private ValidatedState validateAll(ModelGateway.Generated candidate,ModelGateway.StateExtraction extraction,
            boolean bestEffort) {
        require(candidate!=null && candidate.content()!=null && !candidate.content().isBlank(),
                "状态提取缺少最终章节正文");
        require(extraction!=null && extraction.facts()!=null,"状态提取没有返回 facts 数组");
        require(extraction.facts().size()<=200,"状态提取超过单章最多 200 条限制");
        Set<String> keys=new HashSet<>();
        List<Fact> facts=new ArrayList<>();
        List<StateEvidence> evidence=new ArrayList<>();
        for(ModelGateway.ExtractedState item:extraction.facts()) {
            try {
                require(item!=null,"状态提取包含空条目");
                String key=clean(item.key()),type=clean(item.type()),detail=clean(item.detail()),state=clean(item.state());
                require(!key.isBlank() && key.length()<=200,"状态条目需要不超过 200 字符的稳定编号");
                require(keys.add(key),"状态提取不能包含重复编号："+key);
                require(TYPES.contains(type),"状态条目类型不受支持："+type);
                require(STATES.contains(state),"状态条目状态不受支持："+state);
                require(!detail.isBlank() && detail.length()<=2000,"状态条目必须包含简洁、完整的中文描述");
                require(item.evidenceQuotes()!=null && !item.evidenceQuotes().isEmpty(),
                        "状态条目缺少正文原文证据："+key);
                require(item.evidenceQuotes().size()<=MAX_EVIDENCE_QUOTES,
                        "每条状态最多引用 "+MAX_EVIDENCE_QUOTES+" 段正文证据："+key);
                List<String> validatedEvidence=validateEvidence(candidate.content(),key,item.evidenceQuotes());
                facts.add(new Fact(key,type,detail,state));
                evidence.add(new StateEvidence(key,validatedEvidence));
            } catch(Problem invalid) {
                if(!bestEffort) throw invalid;
            }
        }
        require(extraction.entities()!=null && extraction.entities().size()<=100,"单章实体候选不能超过 100 条");
        List<StateEntity> entities=new ArrayList<>(); Set<String> entityKeys=new HashSet<>();
        java.util.Map<String,String> entityTypes=new java.util.LinkedHashMap<>();
        for(ModelGateway.ExtractedEntity item:extraction.entities()) {
            try {
                require(item!=null,"实体提取包含空条目");
                String key=machineKey(item.key(),"实体");
                require(keys.add(key),"状态、实体和关系不能共用重复编号："+key);
                require(entityKeys.add(key),"实体提取不能包含重复编号："+key);
                String type=clean(item.type()),name=clean(item.name()),description=clean(item.description());
                require(ENTITY_TYPES.contains(type),"实体类型不受支持："+type);
                require(!name.isBlank()&&name.length()<=100,"实体名称必须是 1 到 100 字符的文本："+key);
                require(description.length()<=1000,"实体描述不能超过 1000 字符："+key);
                require(item.aliases()!=null&&item.aliases().size()<=10,"实体别名不能超过 10 个："+key);
                List<String> aliases=item.aliases().stream().map(this::clean).filter(value->!value.isBlank()).distinct().toList();
                require(aliases.stream().allMatch(value->value.length()<=100),"单个实体别名不能超过 100 字符："+key);
                List<String> validatedEvidence=validateEvidence(candidate.content(),key,item.evidenceQuotes());
                entities.add(new StateEntity(key,type,name,aliases,description));
                entityTypes.put(key,type);
                evidence.add(new StateEvidence(key,validatedEvidence));
            } catch(Problem invalid) {
                if(!bestEffort) throw invalid;
            }
        }
        require(extraction.relations()!=null && extraction.relations().size()<=200,"单章关系候选不能超过 200 条");
        List<StateRelation> relations=new ArrayList<>();
        for(ModelGateway.ExtractedRelation item:extraction.relations()) {
            try {
                require(item!=null,"关系提取包含空条目");
                String key=machineKey(item.key(),"关系");
                require(keys.add(key),"状态、实体和关系不能共用重复编号："+key);
                require(key.startsWith("relation_"),"关系编号必须以 relation_ 开头："+key);
                require(!key.matches(".*_(?:none|not|unknown|maybe)(?:_|$).*"),"关系编号不能用否定或未知词表达关系："+key);
                String from=machineKey(item.fromEntityKey(),"关系起点实体"),to=machineKey(item.toEntityKey(),"关系终点实体");
                require(entityKeys.contains(from)&&entityKeys.contains(to),"关系两端必须引用本次 entities 中声明的实体："+key);
                require(!from.equals(to),"关系不能连接同一个实体："+key);
                String type=clean(item.type()),detail=clean(item.detail()),state=clean(item.state());
                require(RELATION_TYPES.contains(type),"关系类型不受支持："+type);
                require(RELATION_STATES.contains(state),"关系状态不受支持："+state);
                validateRelationEndpoints(key,type,state,entityTypes.get(from),entityTypes.get(to));
                require(!detail.isBlank()&&detail.length()<=1000,"关系必须包含不超过 1000 字符的中文描述："+key);
                List<String> validatedEvidence=validateEvidence(candidate.content(),key,item.evidenceQuotes());
                relations.add(new StateRelation(key,from,type,to,detail,state));
                evidence.add(new StateEvidence(key,validatedEvidence));
            } catch(Problem invalid) {
                if(!bestEffort) throw invalid;
            }
        }
        AtomicFacts atomic=atomizeFacts(facts,evidence,keys);
        return new ValidatedState(atomic.facts(),List.copyOf(entities),List.copyOf(relations),atomic.evidence());
    }

    public boolean current(Version version) {
        return version!=null && version.stateExtractionRequired
                && version.stateExtractionStatus==com.novelforge.novel.Novel.StateExtractionStatus.SUCCEEDED
                && VERSION.equals(version.stateExtractionPolicyVersion)
                && contentHash(version.content).equals(version.stateExtractionSourceHash);
    }

    public String contentHash(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((content==null?"":content).getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private String clean(String value) { return value==null?"":value.strip(); }

    /**
     * State is an internal index. If a provider combines several durable claims in
     * one item, split that item deterministically instead of asking the author to
     * edit an archive or rewriting the chapter merely to satisfy archive format.
     */
    private AtomicFacts atomizeFacts(List<Fact> facts,List<StateEvidence> allEvidence,Set<String> occupiedKeys) {
        FactAtomicityPolicy atomicity=new FactAtomicityPolicy();
        java.util.Map<String,List<String>> evidenceByKey=new java.util.LinkedHashMap<>();
        for(StateEvidence item:allEvidence) evidenceByKey.put(item.key(),item.evidenceQuotes());
        List<Fact> result=new ArrayList<>();
        List<StateEvidence> factEvidence=new ArrayList<>();
        Set<String> factKeys=facts.stream().map(Fact::key).collect(java.util.stream.Collectors.toSet());
        for(Fact fact:facts) {
            List<String> claims=atomicity.claims(fact.detail());
            if(claims.isEmpty()) claims=List.of(fact.detail());
            for(int index=0;index<claims.size();index++) {
                String key=index==0?fact.key():atomicKey(fact.key(),index+1,occupiedKeys);
                if(index>0) require(occupiedKeys.add(key),"自动拆分状态条目后编号仍然重复："+key);
                result.add(new Fact(key,fact.type(),claims.get(index),fact.state()));
                factEvidence.add(new StateEvidence(key,evidenceByKey.getOrDefault(fact.key(),List.of())));
            }
        }
        List<StateEvidence> nonFactEvidence=allEvidence.stream()
                .filter(item->!factKeys.contains(item.key())).toList();
        factEvidence.addAll(nonFactEvidence);
        return new AtomicFacts(List.copyOf(result),List.copyOf(factEvidence));
    }

    private String atomicKey(String base,int part,Set<String> occupied) {
        String suffix="_part_"+part;
        String stem=base.length()+suffix.length()<=200?base:base.substring(0,200-suffix.length());
        String candidate=stem+suffix;
        int duplicate=2;
        while(occupied.contains(candidate)) {
            String extra="_"+duplicate++;
            int length=Math.min(stem.length(),200-suffix.length()-extra.length());
            candidate=stem.substring(0,length)+suffix+extra;
        }
        return candidate;
    }

    private record AtomicFacts(List<Fact> facts,List<StateEvidence> evidence) {}
    private String machineKey(String raw,String label) {
        String key=clean(raw).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[.\\-\\s]+","_").replaceAll("_+","_")
                .replaceAll("^_+|_+$","");
        require(MACHINE_KEY.matcher(key).matches(),label+"编号必须使用小写字母、数字和下划线，并以字母开头："+key);
        return key;
    }
    private List<String> validateEvidence(String content,String key,List<String> quotes) {
        require(quotes!=null&&!quotes.isEmpty(),"提取条目缺少正文原文证据："+key);
        require(quotes.size()<=MAX_EVIDENCE_QUOTES,
                "每条提取结果最多引用 "+MAX_EVIDENCE_QUOTES+" 段正文证据："+key);
        List<String> validated=new ArrayList<>();
        for(String raw:quotes) {
            String quote=clean(raw);
            require(quote.length()>=2&&quote.length()<=MAX_EVIDENCE_LENGTH,
                    "状态证据长度必须在 2 到 "+MAX_EVIDENCE_LENGTH+" 字符之间："+key);
            String diagnosticQuote=quote.length()<=80?quote:quote.substring(0,80)+"…";
            String supported=content.contains(quote)?quote:repairBoundaryPunctuation(content,quote);
            require(supported!=null,"状态证据并非正文原文："+key+"（模型引文："+diagnosticQuote+"）");
            validated.add(supported);
        }
        return validated.stream().distinct().toList();
    }
    private String repairBoundaryPunctuation(String content,String quote) {
        String best=null;
        int limit=Math.min(4,quote.length()-2);
        for(int left=0;left<=limit;left++) for(int right=0;right<=limit;right++) {
            if(left+right==0||left+right>=quote.length()-1) continue;
            if(!boundaryNoise(quote.substring(0,left))
                    ||!boundaryNoise(quote.substring(quote.length()-right))) continue;
            String candidate=quote.substring(left,quote.length()-right);
            if(candidate.length()>=2&&content.contains(candidate)
                    &&(best==null||candidate.length()>best.length())) best=candidate;
        }
        return best;
    }
    private boolean boundaryNoise(String value) {
        return value.chars().allMatch(ch->Character.isWhitespace(ch)
                ||"，。！？；：、,.!?;:'\"“”‘’（）()[]【】".indexOf(ch)>=0);
    }
    private void validateRelationEndpoints(String key,String type,String state,String fromType,String toType) {
        Set<String> people=Set.of("FAMILY","FRIEND","ALLY","ENEMY","KNOWS","LOVES","COLLEAGUE","DELEGATES_TO");
        if(people.contains(type))
            require("CHARACTER".equals(fromType)&&"CHARACTER".equals(toType),"人物关系两端都必须是人物："+key);
        if(List.of("WORKS_FOR","MEMBER_OF").contains(type))
            require("CHARACTER".equals(fromType)&&"ORGANIZATION".equals(toType),type+" 必须从人物指向组织："+key);
        if("LOCATED_AT".equals(type)) require("LOCATION".equals(toType),"LOCATED_AT 必须指向地点："+key);
        if("OWNS".equals(type))
            require(List.of("CHARACTER","ORGANIZATION").contains(fromType)
                    && List.of("ITEM","LOCATION","ORGANIZATION").contains(toType),"OWNS 的主体或对象类型不合法："+key);
        if("CUSTODIAN_OF".equals(type))
            require(List.of("CHARACTER","ORGANIZATION").contains(fromType)&&"ITEM".equals(toType),
                    "CUSTODIAN_OF 必须从人物或组织指向物品："+key);
        if("FAMILY".equals(type)) require("ACTIVE".equals(state),"失忆、分离或死亡不会让客观亲属关系变为 ENDED："+key);
    }
}
