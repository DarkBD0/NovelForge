package com.novelforge.generation;

import com.novelforge.shared.Problem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateExtractionPolicyTest {
    private final StateExtractionPolicy policy=new StateExtractionPolicy();
    private final ModelGateway.Generated candidate=new ModelGateway.Generated("第一章",
            "陈默把钥匙交给林秋。林秋收好钥匙，答应第二天带到警局。","交接钥匙",List.of(),null);

    @Test void acceptsOnlyEvidenceBackedAtomicState() {
        var extraction=new ModelGateway.StateExtraction(List.of(new ModelGateway.ExtractedState(
                "event_key_handoff","EVENT","陈默已把钥匙交给林秋","ACTIVE",List.of("陈默把钥匙交给林秋"))));

        assertThat(policy.validate(candidate,extraction)).singleElement().satisfies(fact->{
            assertThat(fact.key()).isEqualTo("event_key_handoff");
            assertThat(fact.detail()).isEqualTo("陈默已把钥匙交给林秋");
        });
    }

    @Test void rejectsInventedEvidenceAndDuplicateKeys() {
        var invented=new ModelGateway.StateExtraction(List.of(new ModelGateway.ExtractedState(
                "event_key_handoff","EVENT","钥匙已送到警局","ACTIVE",List.of("钥匙已经送到警局"))));
        assertThatThrownBy(()->policy.validate(candidate,invented)).isInstanceOf(Problem.class)
                .hasMessageContaining("并非正文原文");

        var duplicate=new ModelGateway.StateExtraction(List.of(
                new ModelGateway.ExtractedState("same","EVENT","陈默交出钥匙","ACTIVE",List.of("陈默把钥匙交给林秋")),
                new ModelGateway.ExtractedState("same","CHARACTER","林秋收下钥匙","ACTIVE",List.of("林秋收好钥匙"))));
        assertThatThrownBy(()->policy.validate(candidate,duplicate)).isInstanceOf(Problem.class)
                .hasMessageContaining("重复编号");
    }

    @Test void permitsAnEmptyDeltaWhenChapterCreatesNoDurableState() {
        assertThat(policy.validate(candidate,new ModelGateway.StateExtraction(List.of()))).isEmpty();
    }

    @Test void automaticallySplitsCompoundInternalFactsAndKeepsEvidenceAligned() {
        var extraction=new ModelGateway.StateExtraction(List.of(new ModelGateway.ExtractedState(
                "event_key_handoff","EVENT","陈默把钥匙交给林秋，并要求她第二天带到警局","ACTIVE",
                List.of("陈默把钥匙交给林秋","答应第二天带到警局"))));

        var validated=policy.validateAll(candidate,extraction);

        assertThat(validated.facts()).extracting("key")
                .containsExactly("event_key_handoff","event_key_handoff_part_2");
        assertThat(validated.facts()).extracting("detail")
                .containsExactly("陈默把钥匙交给林秋","要求她第二天带到警局");
        assertThat(validated.evidence()).extracting("key")
                .containsExactly("event_key_handoff","event_key_handoff_part_2");
    }

    @Test void acceptsACompleteEvidenceParagraphLongerThanTheOldDisplayLimit() {
        String paragraph="陈默把钥匙交给林秋。"+"这是仍然属于正文原文的补充说明。".repeat(20);
        var longCandidate=new ModelGateway.Generated("第一章",paragraph,"交接钥匙",List.of(),null);
        var extraction=new ModelGateway.StateExtraction(List.of(new ModelGateway.ExtractedState(
                "event_key_handoff","EVENT","陈默已把钥匙交给林秋","ACTIVE",List.of(paragraph))));

        assertThat(policy.validate(longCandidate,extraction)).hasSize(1);
    }

    @Test void acceptsSeveralExactEvidenceFragmentsFromARealProvider() {
        String content="甲把钥匙交给乙。乙把钥匙收好。乙答应保管钥匙。甲说明钥匙用途。";
        var realCandidate=new ModelGateway.Generated("第一章",content,"交接钥匙",List.of(),null);
        var extraction=new ModelGateway.StateExtraction(List.of(new ModelGateway.ExtractedState(
                "event_key_handoff","EVENT","甲已把钥匙交给乙保管","ACTIVE",
                List.of("甲把钥匙交给乙","乙把钥匙收好","乙答应保管钥匙","甲说明钥匙用途"))));

        assertThat(policy.validate(realCandidate,extraction)).hasSize(1);
    }

    @Test void repairsOnlyExtraBoundaryPunctuationAndPersistsTheExactSourceSubstring() {
        String content="“设备巡检。”她说，“你进去确认一下，里面有没有不该保留的运行痕迹。有就记录。”";
        String providerQuote="“设备巡检。”她说，“你进去确认一下，里面有没有不该保留的运行痕迹。”";
        var chapter=new ModelGateway.Generated("第一章",content,"巡检委托",List.of(),null);
        var extraction=new ModelGateway.StateExtraction(List.of(new ModelGateway.ExtractedState(
                "event_inspection","EVENT","人物受托检查运行痕迹","ACTIVE",List.of(providerQuote))));

        var validated=policy.validateAll(chapter,extraction);
        assertThat(validated.evidence()).singleElement().satisfies(saved->{
            assertThat(saved.evidenceQuotes()).containsExactly("“设备巡检。”她说，“你进去确认一下，里面有没有不该保留的运行痕迹。");
            assertThat(content).contains(saved.evidenceQuotes().getFirst());
        });
    }

    @Test void validatesEvidenceBackedEntitiesAndDirectedRelations() {
        var chen=new ModelGateway.ExtractedEntity("character_chen_mo","CHARACTER","陈默",List.of(),
                "把钥匙交给林秋的人",List.of("陈默把钥匙交给林秋"));
        var lin=new ModelGateway.ExtractedEntity("character_lin_qiu","CHARACTER","林秋",List.of(),
                "收好钥匙并答应送往警局的人",List.of("林秋收好钥匙，答应第二天带到警局"));
        var relation=new ModelGateway.ExtractedRelation("relation_chen_mo_knows_lin_qiu",
                "character_chen_mo","KNOWS","character_lin_qiu","陈默认识林秋并把钥匙交给她",
                "ACTIVE",List.of("陈默把钥匙交给林秋"));

        var validated=policy.validateAll(candidate,new ModelGateway.StateExtraction(List.of(),List.of(chen,lin),List.of(relation)));

        assertThat(validated.entities()).extracting("key").containsExactly("character_chen_mo","character_lin_qiu");
        assertThat(validated.relations()).singleElement().satisfies(saved->{
            assertThat(saved.fromEntityKey()).isEqualTo("character_chen_mo");
            assertThat(saved.toEntityKey()).isEqualTo("character_lin_qiu");
            assertThat(saved.type()).isEqualTo("KNOWS");
        });
        assertThat(validated.evidence()).extracting("key")
                .containsExactly("character_chen_mo","character_lin_qiu","relation_chen_mo_knows_lin_qiu");
    }

    @Test void supportsColleagueDelegationAndTemporaryCustodyWithoutInventingEmploymentOrOwnership() {
        String content="周岚和林澈是同事。周岚委托林澈调查旧站台。林澈暂时保管铜钥匙。";
        var chapter=new ModelGateway.Generated("第一章",content,"委托调查",List.of(),null);
        var zhou=new ModelGateway.ExtractedEntity("character_zhou_lan","CHARACTER","周岚",List.of(),"委托人",List.of("周岚和林澈是同事"));
        var lin=new ModelGateway.ExtractedEntity("character_lin_che","CHARACTER","林澈",List.of(),"受托人",List.of("周岚和林澈是同事"));
        var key=new ModelGateway.ExtractedEntity("item_bronze_key","ITEM","铜钥匙",List.of(),"由林澈暂时保管",List.of("林澈暂时保管铜钥匙"));
        var colleague=new ModelGateway.ExtractedRelation("relation_zhou_lan_colleague_lin_che","character_zhou_lan",
                "COLLEAGUE","character_lin_che","周岚与林澈是同事","ACTIVE",List.of("周岚和林澈是同事"));
        var delegates=new ModelGateway.ExtractedRelation("relation_zhou_lan_delegates_to_lin_che","character_zhou_lan",
                "DELEGATES_TO","character_lin_che","周岚委托林澈调查旧站台","ACTIVE",List.of("周岚委托林澈调查旧站台"));
        var custody=new ModelGateway.ExtractedRelation("relation_lin_che_custodian_of_key","character_lin_che",
                "CUSTODIAN_OF","item_bronze_key","林澈暂时保管铜钥匙","ACTIVE",List.of("林澈暂时保管铜钥匙"));

        var validated=policy.validateAll(chapter,new ModelGateway.StateExtraction(List.of(),List.of(zhou,lin,key),
                List.of(colleague,delegates,custody)));
        assertThat(validated.relations()).extracting("type")
                .containsExactly("COLLEAGUE","DELEGATES_TO","CUSTODIAN_OF");
    }

    @Test void normalizesCommonProviderKeySeparatorsBeforeValidatingReferences() {
        var chen=new ModelGateway.ExtractedEntity("Character.Chen-Mo","CHARACTER","陈默",List.of(),
                "把钥匙交给林秋的人",List.of("陈默把钥匙交给林秋"));
        var lin=new ModelGateway.ExtractedEntity("character.lin qiu","CHARACTER","林秋",List.of(),
                "收好钥匙的人",List.of("林秋收好钥匙"));
        var relation=new ModelGateway.ExtractedRelation("relation.chen-mo-knows-lin-qiu",
                "Character.Chen-Mo","KNOWS","character.lin qiu","陈默认识林秋",
                "ACTIVE",List.of("陈默把钥匙交给林秋"));

        var validated=policy.validateAll(candidate,
                new ModelGateway.StateExtraction(List.of(),List.of(chen,lin),List.of(relation)));

        assertThat(validated.entities()).extracting("key")
                .containsExactly("character_chen_mo","character_lin_qiu");
        assertThat(validated.relations()).singleElement().satisfies(saved->{
            assertThat(saved.key()).isEqualTo("relation_chen_mo_knows_lin_qiu");
            assertThat(saved.fromEntityKey()).isEqualTo("character_chen_mo");
            assertThat(saved.toEntityKey()).isEqualTo("character_lin_qiu");
        });
    }

    @Test void rejectsDanglingRelationAndInventedEntityEvidence() {
        var invented=new ModelGateway.ExtractedEntity("character_chen_mo","CHARACTER","陈默",List.of(),
                "不存在的身份",List.of("陈默是警察"));
        assertThatThrownBy(()->policy.validateAll(candidate,
                new ModelGateway.StateExtraction(List.of(),List.of(invented),List.of())))
                .isInstanceOf(Problem.class).hasMessageContaining("并非正文原文");

        var chen=new ModelGateway.ExtractedEntity("character_chen_mo","CHARACTER","陈默",List.of(),
                "交出钥匙的人",List.of("陈默把钥匙交给林秋"));
        var dangling=new ModelGateway.ExtractedRelation("relation_missing","character_chen_mo","KNOWS",
                "character_not_declared","陈默认识另一人","ACTIVE",List.of("陈默把钥匙交给林秋"));
        assertThatThrownBy(()->policy.validateAll(candidate,
                new ModelGateway.StateExtraction(List.of(),List.of(chen),List.of(dangling))))
                .isInstanceOf(Problem.class).hasMessageContaining("必须引用本次 entities");
    }

    @Test void rejectsSemanticallyInvalidRelationEndpointsAndEndedFamily() {
        var person=new ModelGateway.ExtractedEntity("character_lin_qiu","CHARACTER","林秋",List.of(),
                "收好钥匙的人",List.of("林秋收好钥匙"));
        var location=new ModelGateway.ExtractedEntity("police_station","LOCATION","警局",List.of(),
                "林秋准备带钥匙前往的地点",List.of("第二天带到警局"));
        var worksForLocation=new ModelGateway.ExtractedRelation("relation_lin_qiu_works_for_station",
                "character_lin_qiu","WORKS_FOR","police_station","林秋受雇于警局","ACTIVE",List.of("第二天带到警局"));
        assertThatThrownBy(()->policy.validateAll(candidate,
                new ModelGateway.StateExtraction(List.of(),List.of(person,location),List.of(worksForLocation))))
                .isInstanceOf(Problem.class).hasMessageContaining("必须从人物指向组织");

        var other=new ModelGateway.ExtractedEntity("character_chen_mo","CHARACTER","陈默",List.of(),
                "把钥匙交给林秋的人",List.of("陈默把钥匙交给林秋"));
        var endedFamily=new ModelGateway.ExtractedRelation("relation_lin_qiu_family_chen_mo",
                "character_lin_qiu","FAMILY","character_chen_mo","两人的亲属关系结束","ENDED",
                List.of("陈默把钥匙交给林秋"));
        assertThatThrownBy(()->policy.validateAll(candidate,
                new ModelGateway.StateExtraction(List.of(),List.of(person,other),List.of(endedFamily))))
                .isInstanceOf(Problem.class).hasMessageContaining("亲属关系变为 ENDED");
    }

    @Test void historicalBestEffortKeepsValidMemoryWhileDroppingMalformedRelations() {
        var fact=new ModelGateway.ExtractedState("event_key_handoff","EVENT","陈默已把钥匙交给林秋",
                "ACTIVE",List.of("陈默把钥匙交给林秋"));
        var invalidFact=new ModelGateway.ExtractedState("event_bad_type","OPEN","无效类型",
                "ACTIVE",List.of("林秋收好钥匙"));
        var chen=new ModelGateway.ExtractedEntity("character_chen_mo","CHARACTER","陈默",List.of(),
                "交出钥匙的人",List.of("陈默把钥匙交给林秋"));
        var lin=new ModelGateway.ExtractedEntity("character_lin_qiu","CHARACTER","林秋",List.of(),
                "收好钥匙的人",List.of("林秋收好钥匙"));
        var invalidEntity=new ModelGateway.ExtractedEntity("character_linqiū","CHARACTER","林秋",List.of(),
                "编号含音调符号",List.of("林秋收好钥匙"));
        var invalid=new ModelGateway.ExtractedRelation("relation_chen_mo_works_for_lin_qiu",
                "character_chen_mo","WORKS_FOR","character_lin_qiu","陈默受雇于林秋","ACTIVE",
                List.of("陈默把钥匙交给林秋"));

        var validated=policy.validateAllBestEffort(candidate,
                new ModelGateway.StateExtraction(List.of(fact,invalidFact),List.of(chen,lin,invalidEntity),List.of(invalid)));

        assertThat(validated.facts()).hasSize(1);
        assertThat(validated.entities()).hasSize(2);
        assertThat(validated.relations()).isEmpty();
        assertThat(validated.evidence()).extracting("key")
                .containsExactly("event_key_handoff","character_chen_mo","character_lin_qiu");
    }
}
