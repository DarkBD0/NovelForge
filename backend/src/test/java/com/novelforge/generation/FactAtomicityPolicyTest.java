package com.novelforge.generation;

import com.novelforge.novel.Novel.Fact;
import com.novelforge.novel.Novel.Review;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FactAtomicityPolicyTest {
    private final FactAtomicityPolicy policy=new FactAtomicityPolicy();

    @Test void splitsCompoundFactIntoAuditableAtomicClaims() {
        assertThat(policy.claims("王阿姨藏起现金，并把钥匙交给张洋；张洋离开宿舍。"))
                .containsExactly("王阿姨藏起现金","把钥匙交给张洋","张洋离开宿舍");
    }

    @Test void keepsAdverbInsideASingleConditionAndConsequenceClaim() {
        assertThat(policy.claims("林澈越权开锁会同时暴露他和周岚。"))
                .containsExactly("林澈越权开锁会同时暴露他和周岚");
        assertThat(policy.claims("林澈打开控制柜，同时记录电压变化。"))
                .containsExactly("林澈打开控制柜","记录电压变化");
    }

    @Test void blocksCompoundFactWithoutAskingForExplanatoryProse() {
        var candidate=new ModelGateway.Generated("第一章","王阿姨藏起现金。","摘要",
                List.of(new Fact("event_cash","EVENT","王阿姨藏起现金，并把钥匙交给张洋","ACTIVE")),null);
        Review result=policy.apply(candidate,new Review(true,List.of(),false,false,false,List.of()));

        assertThat(result.passed()).isFalse();
        assertThat(result.issueDetails()).singleElement().satisfies(issue->{
            assertThat(issue.evidence()).contains("当前档案原文",candidate.facts().getFirst().detail());
            assertThat(issue.suggestion()).contains("只拆分当前档案条目","不要为了证明档案向正文补写解释句");
        });
    }
}
