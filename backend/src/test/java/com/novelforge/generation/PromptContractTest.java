package com.novelforge.generation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PromptContractTest {
    @Test void shortPlanUsesCharacterMotivationWithoutRequiringPerChapterReversals() throws IOException {
        assertThat(resource("/prompts/plan-writer.txt"))
                .contains("读取大纲中主角的动机、缺陷、核心冲突和人物关系")
                .contains("名单没有名字→照片地点核对→旧报核对")
                .contains("不得凭空宣布新的身世、罪责或已发生事实")
                .contains("不要求每章有反转、失败或固定事件数");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("前半段两章以上的核心变化主要是把同一物件、地点或旧事核对得更确定")
                .contains("最多标“作者决定”")
                .contains("不强迫每章冲突或反转");
    }

    @Test void criticalEvidenceMustIdentifyItsTargetAndTimeWithoutOverexplainingProse() throws IOException {
        assertThat(resource("/prompts/plan-writer.txt"))
                .contains("只写同一条街不能证实就是目标楼")
                .contains("无日期照片不能凭空提供拍摄年份");
        assertThat(resource("/prompts/writer.txt"))
                .contains("报道若只写街道而无楼号或独立对应线索")
                .contains("不靠旁白硬圆");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("证据来源→人物结论")
                .contains("正文把结论当作后续行动的事实前提")
                .contains("不要要求小说逐项展示普通取证手续");
    }

    @Test void planPromptsCompareBatchBoundaryWithoutTreatingFuturePlansAsOccurredFacts() throws IOException {
        assertThat(resource("/prompts/writer.txt"))
                .contains("context.planComparison.previousBeats")
                .contains("不是已发生正文")
                .contains("有意回环");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("context.planComparison.previousBeats")
                .contains("只因旧规划与新规划相似，不能给当前候选标“必须修正”")
                .contains("最近已确认正文是否已改变前提");
    }

    @Test void completionPromptChecksTheStoryPromiseWithoutBlockingSubjectiveEndingTaste() throws IOException {
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("最终选哪份工作")
                .contains("不能拿规划中的未来结局代替")
                .contains("情绪力度、高潮场面或回报方式不够理想，应作为“作者决定”建议");
    }

    @Test
    void chapterPromptsAllowOffstageFulfillmentAndLaterRevealWithoutInventingFacts() throws IOException {
        assertThat(resource("/prompts/writer.txt"))
                .contains("普通交接不必专门写出")
                .contains("幕后剧情和章外谈话也可以先发生、后揭露")
                .contains("purpose、revealBoundary、endingHook 若彼此矛盾")
                .contains("不能一边写“本章不揭示某人身份”");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("sceneBeats 是可调整的实现路径")
                .contains("不能仅因“明天给名单”之后没有展示递交过程")
                .contains("约定可在章外兑现、被意外打断或暂时隐藏")
                .contains("章外谈话也不自动构成矛盾")
                .contains("若 purpose、revealBoundary、endingHook 互相矛盾");
    }

    @Test
    void planAuditRequiresTwoGroundedExcerptsAndRoutesUpstreamPlanErrors() throws IOException {
        assertThat(resource("/prompts/plan-causality-auditor.txt"))
                .contains("至少一个引文必须来自 candidatePlan")
                .contains("旧版未来规划不是新候选的硬约束")
                .contains("普通生活细节可以省略")
                .contains("criticalTransitions 是内部核对线索")
                .contains("前章不能让同一人物把这件事当已知前提来推断")
                .contains("不能断定照片拍于火灾之前")
                .contains("同一根因只报一项")
                .contains("旧邻居回避回答只能证明她不愿回答")
                .contains("旧地址还有住户或亲属可联系，只是新增线索")
                .contains("此类可行但证据不足的判断优先标“作者决定”")
                .contains("照片中有未列入住户名单的人，不能单凭这一点认定");
        assertThat(resource("/prompts/writer.txt"))
                .contains("criticalTransitions 只用于内部核对")
                .contains("普通名单交接、日常预约、换场或生活细节逐项记录")
                .contains("inferenceBoundary 应写明关键证据至多支持什么")
                .contains("未来真相不自动变成人物当时已知的信息")
                .contains("人物回避提问只能说明其不愿回答")
                .contains("证据边界主要约束人物后续行动和叙事事实")
                .contains("全名必须先有可见来源")
                .contains("旧照片中的人未列入住户名单，不等于他在火灾当晚在场");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("正文服从已发生事实、只是旧规划需要校准")
                .contains("旧地址仍有人居住、邻居说这户一直住着")
                .contains("建议只改摘要，不要求为迁就旧摘要在正文补一段解释")
                .contains("照片背面只留下“秋宁”而人物随后知道全名“林秋宁”")
                .contains("定位“已确认章节规划”并标作者决定");
    }

    @Test
    void outlineAndReviewPromptsCheckPhysicalCausalityOfCoreIncident() throws IOException {
        assertThat(resource("/prompts/outline-architect.txt"))
                .contains("同一事件的时间、人物位置、门与工具的可达性")
                .contains("受害者死在屋外");
        assertThat(resource("/prompts/outline-plot-reviewer.txt"))
                .contains("发生前—发生中—发生后")
                .contains("不能用“技术细节留给正文”放行核心机制");
        assertThat(resource("/prompts/outline-continuity-reviewer.txt"))
                .contains("死者究竟在门内还是门外");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("决定主线真相的事故或犯罪")
                .contains("工具能否触及目标");
    }

    @Test
    void chapterRewriteProtectsSceneIntegrityAndAvoidsOverCompression() throws IOException {
        String prompt=resource("/prompts/chapter-rewriter.txt");

        assertThat(prompt)
                .contains("85%至100%")
                .contains("行动性场景保留人物目标、现实阻力")
                .contains("关系、氛围或铺垫场景可以由人物细微反应与沉默承担作用")
                .contains("禁止把完整场景压成剧情摘要、流水账或连续短句")
                .contains("删除与本章作用无关的天气、水汽、光线堆砌")
                .contains("不得无意义地照抄完整年月日时分");
    }

    @Test
    void newChapterPromptRequiresFullScenesInsteadOfSynopsisProse() throws IOException {
        String prompt=resource("/prompts/writer.txt");

        assertThat(prompt)
                .contains("不得为追求简短把正文压成剧情梗概")
                .contains("行动性场景写清目标、阻力、选择和结果")
                .contains("关系或氛围场景允许用细微反应与沉默承载作用")
                .contains("不要为了显得简洁而把每句话都切成独立短段")
                .contains("并列罗列街道上多家店铺和路人的日常活动")
                .contains("不得把消息记录、档案式完整年月日和时分无意义地抄进叙事")
                .contains("高信息密度章节可以列5至8个节点")
                .contains("recentAverageChapterWords")
                .contains("不得为凑字数重复公司、面试或奖励")
                .contains("不能把同一发现再次当作新的开场事件或章末钩子")
                .contains("兑现过程可以省略，幕后行动可以先隐藏")
                .contains("把它当作承接背景，不重演现场")
                .contains("人物只知道自己经历的事，不知道小说章节编号")
                .contains("不能因为规划写了“记得、知道、预见”，就让人物提前掌握设定禁止掌握的面试内容");
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
        assertThat(prompt).contains("只引用确实可独立删除的那一句");
    }

    @Test
    void chapterBoundarySeparatesFutureIntentFromCompletedAction() throws IOException {
        assertThat(resource("/prompts/writer.txt"))
                .contains("区分“打算去”和“已经去、已经查到”");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("她先去图书馆")
                .contains("不等于已经进入下一章查阅旧报");
    }

    @Test
    void chapterReviewTracksObjectPossessionButDoesNotDemandRoutineHandoffs() throws IOException {
        assertThat(resource("/prompts/writer.txt"))
                .contains("谁拿走、是否归还、此后谁还能拿着")
                .contains("每位被询问者都只说“记不清”")
                .contains("那栋楼出过什么事");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("持有链")
                .contains("不要求主角先告知全名")
                .contains("不把正常留白判硬冲突")
                .contains("是不是发生过什么事");
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
    void contentReviewerDoesNotInventMembershipOfUnnamedCompanyGroups() throws IOException {
        String prompt=resource("/prompts/reviewer.txt");

        assertThat(prompt)
                .contains("未逐一列名的集合")
                .contains("第一次被点名不等于它一定在集合之外")
                .contains("章节编号或写作位置指代故事内时间")
                .contains("passed 仍为 true");
    }

    @Test
    void chapterPromptPrioritizesCausalBeatsOverConflictingEndingHookAndAvoidsSceneReplay() throws IOException {
        String writer=resource("/prompts/writer.txt");
        assertThat(writer)
                .contains("purpose、sceneBeats 与 endingHook 必须指向同一因果顺序")
                .contains("不为凑章末句倒置两场戏")
                .contains("参观产线—问实习经验—逐条朗读工资报到日—盖章打勾");
    }

    @Test
    void chapterPromptDistinguishesCompanyIssuedOfferFromLaterReceiptSignature() throws IOException {
        assertThat(resource("/prompts/writer.txt"))
                .contains("公司已完成内部审批、签发并交付")
                .contains("主角的签收回执可以在之后填写")
                .contains("未获公司签发的草稿当作已得意向");
    }

    @Test
    void genrePlanningRejectsCountOnlyEscalationWithoutForcingImmediatePayoff() throws IOException {
        assertThat(resource("/prompts/writer.txt"))
                .contains("不要为了补足目标字数")
                .contains("允许先受挫再翻盘，不规定每一章都要爽");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("换公司、换人名、增加清单数字")
                .contains("连续三章以上重复同一种交材料、核条款、拿意向的流程")
                .contains("相似事件若构成有意蓄势");
    }

    @Test
    void referralOfPreviouslyRejectedOpportunityNeedsNarrativeJustification() throws IOException {
        assertThat(resource("/prompts/writer.txt"))
                .contains("此前因具体缺陷拒绝的机会介绍给其他人物")
                .contains("不能默认它突然成了合适的帮助");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("先前因明确缺陷而拒绝的机会推荐给身边人")
                .contains("至少标为“作者决定”");
    }

    @Test
    void completionReviewerReconcilesOpenMemoryAgainstLaterStory() throws IOException {
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("completionOpenForeshadowLeads")
                .contains("不是自动判失败的清单")
                .contains("检索后续已确认正文与摘要")
                .contains("不要把未回复的每条消息当成全书伏笔");
    }

    @Test
    void scarcityBasedPayoffIsNotSilentlyUndermined() throws IOException {
        assertThat(resource("/prompts/writer.txt"))
                .contains("大量可替代候选人")
                .contains("会抹掉前面建立的谈判筹码");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("吸引点依赖唯一候选人或信息独占")
                .contains("削弱主角筹码");
    }

    @Test
    void chapterUsesConfirmedAnswerInsteadOfReplayingStalePlanQuestion() throws IOException {
        assertThat(resource("/prompts/writer.txt"))
                .contains("chapterBrief.planEchoResolution")
                .contains("不能仅因为 sceneBeats 又写“追问”就重演问答");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("不能为了逐字复现规划而要求人物重问一次")
                .contains("重复问答算作本章推进");
    }

    @Test
    void shuangEndingChecksNetPayoffWithoutDemandingUniversalWins() throws IOException {
        assertThat(resource("/prompts/outline-plot-reviewer.txt"))
                .contains("最终选择带来的实际净收益")
                .contains("不要机械要求每章都赢");
        assertThat(resource("/prompts/writer.txt"))
                .contains("规划未交代的重大负面条件")
                .contains("无意义的新消息冲淡已发生的选择");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("实质削弱既定兑现")
                .contains("不能仅因规划未写就判必须修正")
                .contains("手续细节淹没");
    }

    @Test
    void shortRealistShuangOutlineDoesNotBecomeOperationsManual() throws IOException {
        assertThat(resource("/prompts/outline-foundation.txt"))
                .contains("经营手续成为主线");
        assertThat(resource("/prompts/writer.txt"))
                .contains("收入或文件只作为结果证据")
                .contains("经营流程说明书");
        assertThat(resource("/prompts/outline-plot-reviewer.txt"))
                .contains("必要可信边界错当成戏剧主线");
        assertThat(resource("/prompts/writer.txt"))
                .contains("完整结构不等于冗长填表")
                .contains("确保最终 JSON 完整闭合");
    }

    @Test
    void narrativeProgressionIsCheckedAcrossTheWholePlanWithoutForcingFailureInEveryChapter() throws IOException {
        assertThat(resource("/prompts/writer.txt"))
                .contains("worldRules 可以为 []")
                .contains("检查本批所有相邻章节，而不只检查开篇三章")
                .contains("不要为了制造波折机械安排每章失败");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("覆盖当前整批尚未写出的所有章节")
                .contains("至少三章当前规划里可核对的重复目的和结果")
                .contains("则至多“作者决定”");
        assertThat(resource("/prompts/outline-plot-reviewer.txt"))
                .contains("谁因自己的目标阻拦")
                .contains("不能代替应在场景中发生的决定和后果");
    }

    @Test
    void outlineReviewChecksActualPayoffMysteryTrapAndRuleSettlement() throws IOException {
        assertThat(resource("/prompts/outline-foundation.txt"))
                .contains("拿到入场资格");
        assertThat(resource("/prompts/writer.txt"))
                .contains("进入下一轮")
                .contains("对手知道诱饵")
                .contains("不可转移、无例外")
                .contains("原始简介不是可随意删改的候选摘要");
        assertThat(resource("/prompts/outline-plot-reviewer.txt"))
                .contains("为何选这些受害者")
                .contains("对手怎样获知诱饵")
                .contains("物证是被取走还是放回")
                .contains("作为“作者决定”")
                .contains("两家公司已满足“多家公司”")
                .contains("先对照用户原始简介")
                .contains("未逐项复述“被控制、移交”等结果不等于矛盾");
        assertThat(resource("/prompts/outline-continuity-reviewer.txt"))
                .contains("旧债务、伤势、抵押")
                .contains("candidate.summary")
                .contains("同一时间、同一对象、同一范围内无法同时成立")
                .contains("摘要不必复述所有事件");
    }

    @Test
    void outlineArchitectHasFocusedPremiseAndGenreContract() throws IOException {
        assertThat(resource("/prompts/outline-architect.txt"))
                .contains("只输出一个完整、合法的 JSON 对象")
                .contains("接连有人失踪")
                .contains("进入下一轮")
                .contains("对手如何获知诱饵")
                .contains("不可转移、无例外")
                .contains("outlineSpec 使用 schemaVersion=\"3\"")
                .doesNotContain("CHAPTER 文风规则");
    }

    @Test
    void readerExpectationAndDramaticChoiceFlowFromOutlineIntoChapterPlanning() throws IOException {
        assertThat(resource("/prompts/outline-architect.txt"))
                .contains("每个阶段都要回答一个具体的读者期待")
                .contains("dramaticTurn")
                .contains("普通交接和生活待办不是读者承诺");
        assertThat(resource("/prompts/writer.txt"))
                .contains("先从已确认大纲的阶段期待、阻力与抉择、阶段兑现和后续变化反推本批章节")
                .contains("允许一章只铺设期待、加深阻力或改变关系")
                .contains("人物只能依据自己当时知道的内容行动");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("读者体验偏弱但仍有合理作用时列“作者决定”或“建议优化”")
                .contains("普通约定无需逐项安排上镜");
    }

    @Test
    void proceduralCheckpointsDoNotBecomeReaderFacingPlotByDefault() throws IOException {
        assertThat(resource("/prompts/writer.txt"))
                .contains("读者实际经历的变化")
                .contains("章外承接或内部状态记录")
                .contains("先呈现人物选择及其可感的失去或得到");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("不可凭程序词汇直接判重复")
                .contains("不以审美判断阻止确认");
    }

    @Test
    void newBatchPlannerHasFocusedReaderProgressionContract() throws IOException {
        String prompt=resource("/prompts/plan-writer.txt");
        assertThat(prompt)
                .contains("章节规划 Agent")
                .contains("新线索应让多个解释竞争或让人物改变行动")
                .contains("不为凑 targetWords、章节数或固定批次长度拆开同一场核验")
                .contains("HANDOFF_CALIBRATION")
                .contains("UPSTREAM_REPLAN")
                .contains("criticalTransitions")
                .contains("outlineSpec")
                .doesNotContain("CHAPTER 文风规则");
    }

    @Test
    void chapterPlanningAndReviewDoNotImposeUniversalEventDensity() throws IOException {
        assertThat(resource("/prompts/writer.txt"))
                .contains("不设统一的每章事件数、阻力数或反转数")
                .contains("安静章节也可以通过人物感受、关系距离、信息含义或氛围积累成立")
                .contains("不要求交替安排固定类型的阻力");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("不能统一要求每章都有外在阻力、反转或固定数量事件")
                .contains("可建议压缩场面、缩短建议篇幅或合并章节")
                .contains("有意回环与逐渐累积的压迫感不等于注水");
        assertThat(resource("/prompts/chapter-rewriter.txt"))
                .contains("不硬加阻力或反转");
        assertThat(resource("/prompts/style-reviewer.txt"))
                .contains("安静章节用于建立氛围或人物感受的细节不因此报错");
    }

    @Test
    void finalBatchAndFutureTimeSlotsStayCausallyConsistent() throws IOException {
        assertThat(resource("/prompts/writer.txt"))
                .contains("就必须设 finalBatch=true")
                .contains("获得未来四晚的经营时段")
                .contains("不能只救出最近一名失踪者")
                .contains("钥匙插在锁孔而非门框")
                .contains("何时被替换或新增");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("错误地要求用户再生成一批规划")
                .contains("租约未签时也不能提前")
                .contains("其他人仍下落不明")
                .contains("不能无交代说旧钥匙直接开新挂锁")
                .contains("核对跨章关键器物状态与可达路径");
    }

    @Test
    void chapterWritingAndReviewRespectEvidenceAgeAndReaderMomentum() throws IOException {
        assertThat(resource("/prompts/writer.txt"))
                .contains("逐站办事清单")
                .contains("旧照片只能证明拍摄当时")
                .contains("照片背面没有编号时");
        assertThat(resource("/prompts/reviewer.txt"))
                .contains("证据载体与产生时间")
                .contains("不能直接证明现在的现场")
                .contains("连续只是顺利领取资料")
                .contains("不等于已确认“她从未来过”")
                .contains("不能仅从外面把钥匙穿过门板插入内门锁孔");
        assertThat(resource("/prompts/chapter-rewriter.txt"))
                .contains("逐站办事清单")
                .contains("旧照片只证明拍摄时")
                .contains("场景节点不是要求逐项展示技术推理");
        assertThat(resource("/prompts/rewriter.txt"))
                .contains("仅修改 summary 或 title 不算完成正文修订")
                .contains("不要在中段和章末各补一遍");
        assertThat(resource("/prompts/style-reviewer.txt"))
                .contains("同时补在中段和章末")
                .contains("连续大段罗列锁孔、灰尘、门框");
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
