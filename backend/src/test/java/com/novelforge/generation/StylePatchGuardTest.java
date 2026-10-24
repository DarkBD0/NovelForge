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

    @Test void rejectsDeletingOnlyHalfOfDialogueQuotation() {
        Version dialogue=base();
        dialogue.content="“我说我不知道。”他说，“我确实不知道。”";
        dialogue.summary="双方没有决定先后顺序。";
        dialogue.facts=List.of();
        RewritePatch patch=new RewritePatch(List.of(new RewritePatch.Operation(
                "REPLACE_TEXT","content",null,"他说，“我确实不知道。","",null,null,null,null)));
        assertThatThrownBy(()->StylePatchGuard.apply(dialogue,patch,Kind.CHAPTER))
                .hasMessageContaining("只包含对话引号的一半");
    }

    @Test void rejectsDeletionThatLeavesACommaAtParagraphEnd() {
        Version chapter=base();
        chapter.content="陈迟把名片放进口袋，没有扔，也没有收得多郑重。\n\n罗建走开。";
        chapter.summary="陈迟收到名片，罗建离开。";
        chapter.facts=List.of();
        RewritePatch patch=new RewritePatch(List.of(new RewritePatch.Operation(
                "REPLACE_TEXT","content",null,"没有扔，也没有收得多郑重。","",null,null,null,null)));
        assertThatThrownBy(()->StylePatchGuard.apply(chapter,patch,Kind.CHAPTER))
                .hasMessageContaining("段落末尾留下逗号");
    }

    @Test void deletionDoesNotLeaveEmptyParagraphs() {
        Version chapter=base();
        chapter.content="他听见脚步。\n\n这是没有必要的解释。\n\n他抬头。";
        chapter.summary="他听见脚步并抬头。";
        chapter.facts=List.of();
        RewritePatch patch=new RewritePatch(List.of(new RewritePatch.Operation(
                "REPLACE_TEXT","content",null,"这是没有必要的解释。","",null,null,null,null)));
        assertThat(StylePatchGuard.apply(chapter,patch,Kind.CHAPTER).content())
                .isEqualTo("他听见脚步。\n\n他抬头。");
    }
}
