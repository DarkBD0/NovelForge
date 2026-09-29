package com.novelforge.generation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PromptContractTest {
    @Test
    void chapterRewriteProtectsSceneIntegrityAndAvoidsOverCompression() throws IOException {
        String prompt=resource("/prompts/chapter-rewriter.txt");

        assertThat(prompt)
                .contains("85%至100%")
                .contains("人物目标、现实阻力、试探或互动、选择或行动、结果以及必要情绪影响")
                .contains("禁止把完整场景压成剧情摘要、流水账或连续短句")
                .contains("删除单纯的天气、水汽、光线铺陈")
                .contains("不得无意义地照抄完整年月日时分");
    }

    @Test
    void newChapterPromptRequiresFullScenesInsteadOfSynopsisProse() throws IOException {
        String prompt=resource("/prompts/writer.txt");

        assertThat(prompt)
                .contains("不得为追求简短把正文压成剧情梗概")
                .contains("人物目标、现实阻力、选择或行动、结果及必要情绪影响")
                .contains("不要为了显得简洁而把每句话都切成独立短段")
                .contains("并列罗列街道上多家店铺和路人的日常活动")
                .contains("不得把消息记录、档案式完整年月日和时分无意义地抄进叙事");
    }

    @Test
    void styleReviewIsAdvisoryAndOnlyRequestsLocalChanges() throws IOException {
        String prompt=resource("/prompts/style-reviewer.txt");

        assertThat(prompt)
                .contains("passed 必须始终为 true")
                .contains("她真的想不起来，不是回避，不是假装")
                .contains("完整年月日时分")
                .contains("不要建议整章重写")
                .contains("一次局部删除或精确替换")
                .contains("原文：“当前正文中可直接删改的完整连续片段”")
                .contains("逐字复制当前正文中唯一出现的完整连续片段")
                .contains("损失事件、人物选择、时间线、线索、因果、伏笔或新信息");
    }

    @Test
    void continuityReviewRequiresSameTimeProofAndRejectsNormalProgression() throws IOException {
        String prompt=resource("/prompts/continuity-reviewer.txt");

        assertThat(prompt)
                .contains("issues 必须是结构化数组，最多两项")
                .contains("CONTINUITY:OBJECT_LOCATION:HIGH")
                .contains("同一时点：“是”")
                .contains("推进授权：“无”")
                .contains("状态推进规则高于前章状态")
                .contains("照片、复印件、录像和物品本体可以同时存在")
                .contains("必须报告的位置冲突")
                .contains("同一个发现事件、同一把钥匙、两个互斥地点")
                .contains("直接忽略，而不是降级保存");
    }

    @Test
    void outlinePipelineKeepsWriterAndAdvisersWithinTheirOwnBoundaries() throws IOException {
        String foundation=resource("/prompts/outline-foundation.txt");
        String writer=resource("/prompts/writer.txt");
        String continuity=resource("/prompts/outline-continuity-reviewer.txt");
        String plot=resource("/prompts/outline-plot-reviewer.txt");

        assertThat(foundation)
                .contains("只提供创作参谋材料，不编写完整大纲")
                .contains("限制、代价和信息来源")
                .contains("不得自行声称用户指定了题材、结局或人物关系");
        assertThat(writer)
                .contains("你是完整大纲的唯一主笔")
                .contains("outlineFoundation")
                .contains("参谋材料是尚未确认的创作假设，不是权威事实")
                .contains("先确定明确结局并反推主线因果")
                .contains("outlineSpec 必须使用下面的完整结构")
                .contains("所有篇章预算之和必须严格等于 context.targetWords")
                .contains("authorityStatus 都只能是 CANDIDATE")
                .contains("作者可读大纲由系统根据 outlineSpec 生成");
        assertThat(continuity)
                .contains("不修改大纲，不评价文风")
                .contains("两段可以直接证明互斥的原文");
        assertThat(plot)
                .contains("大纲不是逐章剧本")
                .contains("明确缺少结局")
                .contains("不得靠重复冲突或无结果支线注水");
    }

    private String resource(String path) throws IOException {
        try (InputStream stream=getClass().getResourceAsStream(path)) {
            assertThat(stream).as("classpath resource %s",path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
