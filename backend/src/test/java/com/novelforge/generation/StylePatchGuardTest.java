package com.novelforge.generation;

import com.novelforge.novel.Novel.Fact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Version;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class StylePatchGuardTest {
    private Version base() {
        Version version=new Version(); version.title="雨夜";
        version.content="雨一直下。陈默在案卷上写下四个字：人为布置。";
        version.summary="陈默判断线索有人为布置痕迹。";
        version.facts=List.of(new Fact("event_layout","EVENT","线索存在人为布置痕迹","ACTIVE"));
        return version;
    }

    @Test void appliesOnlySmallExactContentReduction() {
        RewritePatch patch=new RewritePatch(List.of(new RewritePatch.Operation(
                "REPLACE_TEXT","content",null,"雨一直下。","",null,null,null,null)));
        var result=StylePatchGuard.apply(base(),patch,Kind.CHAPTER);
        assertThat(result.content()).isEqualTo("陈默在案卷上写下四个字：人为布置。");
        assertThat(result.summary()).isEqualTo(base().summary);
        assertThat(result.facts()).isEqualTo(base().facts);
    }

    @Test void rejectsMetadataChangesAnyRewriteAndProtectedTokenChanges() {
        RewritePatch summary=new RewritePatch(List.of(new RewritePatch.Operation(
                "SET_FIELD","summary",null,null,null,"另一个摘要",null,null,null)));
        RewritePatch expansion=new RewritePatch(List.of(new RewritePatch.Operation(
                "REPLACE_TEXT","content",null,"雨一直下。","雨一直下，街道像一条漫长的河。",null,null,null,null)));
        RewritePatch number=new RewritePatch(List.of(new RewritePatch.Operation(
                "REPLACE_TEXT","content",null,"四个字","三个字",null,null,null,null)));
        RewritePatch equalLengthRewrite=new RewritePatch(List.of(new RewritePatch.Operation(
                "REPLACE_TEXT","content",null,"雨一直下。","门一直开。",null,null,null,null)));
        assertThatThrownBy(()->StylePatchGuard.apply(base(),summary,Kind.CHAPTER)).hasMessageContaining("只能精确替换或删除正文");
        assertThatThrownBy(()->StylePatchGuard.apply(base(),expansion,Kind.CHAPTER)).hasMessageContaining("只允许删除明确赘句");
        assertThatThrownBy(()->StylePatchGuard.apply(base(),number,Kind.CHAPTER)).hasMessageContaining("只允许删除明确赘句");
        assertThatThrownBy(()->StylePatchGuard.apply(base(),equalLengthRewrite,Kind.CHAPTER)).hasMessageContaining("只允许删除明确赘句");
    }
}
