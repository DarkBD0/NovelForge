package com.novelforge.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel.Action;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoleContextCompilerTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final RoleContextCompiler compiler=new RoleContextCompiler(mapper,new AgentContextPolicy());

    @Test void writerKeepsDesignSummariesRetrievalAndOnlyThreeRecentFullTexts() throws Exception {
        ModelGateway.Request compiled=compiler.compile(request(),AgentRole.CHAPTER_WRITER);
        var json=mapper.readTree(compiled.context().json());

        assertThat(json.path("agentContext").path("role").asText()).isEqualTo("CHAPTER_WRITER");
        assertThat(json.has("retrievedConfirmedHistory")).isTrue();
        assertThat(json.has("formalStructuredMemory")).isFalse();
        assertThat(json.has("conversationBrief")).isFalse();
        assertThat(json.path("acceptedReferences").get(0).has("content")).isFalse();
        assertThat(json.path("acceptedReferences").get(1).path("content").asText()).isEqualTo("第三章全文");
        assertThat(json.path("acceptedReferences").get(2).path("content").asText()).isEqualTo("大纲全文");
        assertThat(json.path("acceptedReferences").size()).isEqualTo(3);
        assertThat(json.has("unrelatedInternalField")).isFalse();
        assertThat(compiled.context().sourceVersions()).containsExactly("chapter-1","chapter-3","outline-1","retrieved-1");
    }

    @Test void structuredMemoryShadowAddsConfirmedEntitiesAndRelationsWithoutChangingOfficialPolicy() throws Exception {
        ModelGateway.Request official=compiler.compile(request(),AgentRole.CHAPTER_WRITER);
        ModelGateway.Request shadow=compiler.compileStructuredMemoryShadow(request(),AgentRole.CHAPTER_WRITER);
        var officialJson=mapper.readTree(official.context().json());
        var shadowJson=mapper.readTree(shadow.context().json());

        assertThat(officialJson.has("formalStructuredMemory")).isFalse();
        assertThat(shadowJson.path("formalStructuredMemory").path("entities").get(0).path("name").asText())
                .isEqualTo("林澈");
        assertThat(shadowJson.path("formalStructuredMemory").path("relations").get(0).path("type").asText())
                .isEqualTo("COLLEAGUE");
        assertThat(shadowJson.path("agentContext").path("policyVersion").asText())
                .isEqualTo(AgentContextPolicy.STRUCTURED_MEMORY_SHADOW_VERSION);
        assertThat(official.context().sourceVersions()).doesNotContain("entity-source");
        assertThat(shadow.context().sourceVersions()).contains("entity-source");
    }

    @Test void historicalContinuityShadowKeepsVerifiedMemoryButDropsCompetingNarrativeContext() throws Exception {
        ModelGateway.Request focused=compiler.compileHistoricalContinuityShadow(request());
        var json=mapper.readTree(focused.context().json());

        assertThat(json.path("formalStructuredMemory").path("entities").get(0).path("name").asText())
                .isEqualTo("林澈");
        assertThat(json.path("agentContext").path("policyVersion").asText())
                .isEqualTo(AgentContextPolicy.HISTORICAL_CONTINUITY_SHADOW_VERSION);
        assertThat(json.path("agentContext").path("role").asText()).isEqualTo("CONTINUITY_AUDITOR");
        assertThat(json.path("historicalClaimEvidencePairs").path("pairs").get(0)
                .path("historicalQuote").asText()).isEqualTo("林澈曾与周岚共事");
        assertThat(json.has("acceptedReferences")).isFalse();
        assertThat(json.has("canonBeforeChapter")).isFalse();
        assertThat(json.has("currentChapterPlan")).isFalse();
        assertThat(json.has("previousChapterSummary")).isFalse();
        assertThat(json.has("retrievedConfirmedHistory")).isFalse();
        assertThat(json.has("revisionTarget")).isFalse();
        assertThat(focused.context().sourceVersions()).containsExactly("entity-source");
    }

    @Test void styleAuditorSeesCandidateAndCanonButNoBroadHistoryOrRetrieval() throws Exception {
        ModelGateway.Request compiled=compiler.compile(request(),AgentRole.STYLE_AUDITOR);
        var json=mapper.readTree(compiled.context().json());

        assertThat(json.has("revisionTarget")).isTrue();
        assertThat(json.has("canonBeforeChapter")).isTrue();
        assertThat(json.has("acceptedReferences")).isFalse();
        assertThat(json.has("previousChapterSummary")).isFalse();
        assertThat(json.has("retrievedConfirmedHistory")).isFalse();
        assertThat(json.has("conversationBrief")).isFalse();
        assertThat(compiled.context().sourceVersions()).containsExactly("chapter-1","target-1");
    }

    @Test void architectKeepsFoundationAndAcceptedConversationIntent() throws Exception {
        ModelGateway.Request compiled=compiler.compile(request(),AgentRole.STORY_ARCHITECT);
        var json=mapper.readTree(compiled.context().json());

        assertThat(json.has("outlineFoundation")).isTrue();
        assertThat(json.has("conversationBrief")).isTrue();
        assertThat(json.has("retrievedConfirmedHistory")).isFalse();
    }

    private ModelGateway.Request request() {
        String json="""
                {
                  "title":"角色隔离","synopsis":"测试","requirements":"保持事实",
                  "conversationBrief":{"baseVersionId":"outline-1"},
                  "stateModel":{"authorityOrder":[]},
                  "targetWords":10000,"nextChapter":5,
                  "canonBeforeChapter":[{"sourceVersionId":"chapter-1","detail":"正式事实"}],
                  "formalStructuredMemory":{
                    "authorityState":"CONFIRMED_MYSQL_STRUCTURED_MEMORY",
                    "entities":[{"key":"character_lin_che","type":"CHARACTER","name":"林澈","sourceVersionId":"entity-source"}],
                    "relations":[{"key":"relation_colleague","fromEntityKey":"character_lin_che","type":"COLLEAGUE","toEntityKey":"character_zhou_lan","sourceVersionId":"entity-source"}]
                  },
                  "historicalClaimEvidencePairs":{"policyVersion":"historical-claim-evidence-pairing-v1","pairs":[
                    {"candidateClaim":"林澈否认共事","historicalQuote":"林澈曾与周岚共事","sourceVersionId":"entity-source"}
                  ]},
                  "currentChapterPlan":{"title":"第五章","purpose":"回查旧线索"},
                  "chapterBrief":{"coreChange":"找到证据"},
                  "previousChapterSummary":"第四章摘要",
                  "acceptedReferences":[
                    {"kind":"CHAPTER","chapter":1,"versionId":"chapter-1","authorityState":"CONFIRMED","summary":"第一章摘要","content":"第一章全文"},
                    {"kind":"CHAPTER","chapter":3,"versionId":"chapter-3","authorityState":"CONFIRMED","summary":"第三章摘要","content":"第三章全文"},
                    {"kind":"OUTLINE","chapter":0,"versionId":"outline-1","authorityState":"CONFIRMED","summary":"大纲摘要","content":"大纲全文"},
                    {"kind":"CHAPTER","chapter":4,"versionId":"draft-4","authorityState":"UNCONFIRMED_CANDIDATE","summary":"未确认内容","content":"不能泄漏"},
                    {"kind":"CHAPTER","chapter":6,"versionId":"future-6","authorityState":"CONFIRMED","summary":"未来章节","content":"不能提前泄漏"}
                  ],
                  "retrievedConfirmedHistory":{"hits":[{"sourceVersionId":"retrieved-1","chapterNumber":1,"excerpt":"旧线索"}]},
                  "revisionTarget":{"versionId":"target-1","title":"第五章候选","content":"候选正文"},
                  "repairReasons":["修正冲突"],
                  "outlineFoundation":{"content":"参谋材料"},
                  "outlineFoundationAuthority":"CREATIVE_ASSUMPTION_NOT_CONFIRMED",
                  "unrelatedInternalField":{"secret":"任何未列入白名单的新字段不得自动泄漏"}
                }
                """;
        var context=new ContextAssembler.Context(json,
                List.of("chapter-1","chapter-3","outline-1","retrieved-1","target-1","draft-4","future-6","entity-source"),5,1);
        return new ModelGateway.Request(Action.CHAPTER,null,null,context,"");
    }
}
