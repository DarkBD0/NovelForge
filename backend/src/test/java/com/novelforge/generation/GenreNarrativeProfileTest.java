package com.novelforge.generation;

import com.novelforge.novel.Novel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenreNarrativeProfileTest {
    private final GenreNarrativeProfile profiles=new GenreNarrativeProfile(new RequirementAuthority());

    @Test void explicitShuangInstructionWinsOverIncidentalMysteryLanguage() {
        Novel novel=new Novel(); novel.title="重生秋招";
        novel.synopsis="主角一开始怀疑市场异常，随后靠信息差拿下全部录用通知";
        novel.requirements="这是纯粹的爽文，不要安排重生原因调查，也不要写成悬疑";

        assertThat(profiles.resolve(novel).mode()).isEqualTo(GenreNarrativeProfile.Mode.SHUANG);
        assertThat(profiles.resolve(novel).chapterStyle()).contains("允许先受挫再兑现");
        assertThat(profiles.resolve(novel).chapterStyle()).contains("流程只写对选择真正有用的部分");
    }

    @Test void resolvesActualSuspenseProjectSeparately() {
        Novel novel=new Novel(); novel.title="雨夜失踪案"; novel.synopsis="通过线索追查失踪真相";
        novel.requirements="现代悬疑推理小说";

        assertThat(profiles.resolve(novel).mode()).isEqualTo(GenreNarrativeProfile.Mode.SUSPENSE);
        assertThat(profiles.resolve(novel).chapterStyle()).contains("一个真正改变判断的发现胜过连续罗列");
        assertThat(profiles.resolve(novel).planningRules()).anyMatch(rule->rule.contains("不要求每次查看都得到新证据"));
    }

    @Test void generalProfileAllowsQuietRelationshipAndAtmosphereChapters() {
        Novel novel=new Novel(); novel.title="旧城里的夏天"; novel.requirements="日常与人物关系";

        assertThat(profiles.resolve(novel).mode()).isEqualTo(GenreNarrativeProfile.Mode.GENERAL);
        assertThat(profiles.resolve(novel).planningRules())
                .anyMatch(rule->rule.contains("不统一要求行动、阻力和反转"))
                .anyMatch(rule->rule.contains("不强制悬念钩子"));
    }
}
