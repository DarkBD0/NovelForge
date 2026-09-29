package com.novelforge;

import com.novelforge.generation.ModelGateway;
import com.novelforge.generation.ReviewPolicy;
import com.novelforge.generation.StyleReviewPolicy;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.task.TaskService;
import com.novelforge.shared.Problem;
import com.novelforge.workflow.WorkflowRules;
import com.novelforge.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:style-polish-failure;DB_CLOSE_DELAY=-1")
class StylePolishFailureIntegrationTest {
    @Autowired WorkflowService workflow;
    @Autowired WorkflowRules rules;
    @Autowired NovelRepository repository;
    @Autowired TaskService tasks;
    @MockitoBean ModelGateway model;

    @Test void failedContentRecheckLeavesOriginalAsLatestAndDoesNotAdvanceRevision() {
        when(model.ready()).thenReturn(true);
        when(model.mode()).thenReturn("http");
        ReviewIssue conflict=new ReviewIssue("当前正文","自动删减后档案事实失去正文依据",
                "候选原文：“地上有翻找痕迹。”","保留承载调查结论的原句","必须修正");
        when(model.review(any(),any())).thenReturn(
                new Review(false,List.of(conflict.text()),false,false,false,List.of(conflict)));

        Novel n=workflow.create("测试小说","测试自动文风删减失败时的保存边界",1000,"保持事实一致");
        String id=n.id;
        n=repository.update(id,stored->{
            Artifact chapter=new Artifact(); chapter.kind=Kind.CHAPTER; chapter.chapterNumber=1;
            Version version=new Version(); version.title="第一章";
            version.content="地上有翻找痕迹。有人比他先到，在找一样还没找到的东西。";
            version.summary="陈默判断有人抢先翻找，目标尚未找到。";
            version.facts=List.of(new Fact("event_search","EVENT","有人抢先翻找，目标尚未找到。","ACTIVE"));
            version.review=new Review(true,List.of(),false,false,false,List.of());
            version.reviewRevision=stored.revision; version.reviewPolicyVersion=ReviewPolicy.VERSION;
            ReviewIssue style=new ReviewIssue("当前正文","结论属于重复解释",
                    "原文：“有人比他先到，在找一样还没找到的东西。”","删除这句重复解释","建议优化");
            version.styleReview=new Review(true,List.of(style.text()),false,false,false,List.of(style));
            version.styleReviewPolicyVersion=StyleReviewPolicy.VERSION;
            chapter.versions.add(version); stored.artifacts.add(chapter); return stored;
        });
        Artifact chapter=n.artifacts.getLast(); String originalVersionId=chapter.latest().id; long originalRevision=n.revision;

        Task task=tasks.submitStylePolish(id,chapter.id,Novel.uid(),n.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->!List.of(TaskStatus.QUEUED,TaskStatus.RUNNING)
                .contains(tasks.find(repository.get(id),task.id).status));

        Novel after=repository.get(id); Task result=tasks.find(after,task.id);
        assertThat(result.status).isEqualTo(TaskStatus.FAILED);
        assertThat(result.error).contains("未创建候选版本").contains("原内容保持不变");
        assertThat(after.revision).isEqualTo(originalRevision);
        assertThat(rules.artifact(after,chapter.id).latest().id).isEqualTo(originalVersionId);
        assertThat(rules.artifact(after,chapter.id).versions).hasSize(1);
    }

    @Test void failedStyleRecheckAlsoLeavesOriginalAsLatestAndDoesNotAdvanceRevision() {
        when(model.ready()).thenReturn(true);
        when(model.mode()).thenReturn("http");
        when(model.review(any(),any())).thenReturn(new Review(true,List.of(),false,false,false,List.of()));
        when(model.styleReview(any(),any())).thenThrow(new Problem(502,"文风复查模拟失败"));

        Novel n=workflow.create("测试小说","测试文风复查失败前不保存自动删减版本",1000,"保持事实一致");
        String id=n.id;
        n=repository.update(id,stored->{
            Artifact chapter=new Artifact(); chapter.kind=Kind.CHAPTER; chapter.chapterNumber=1;
            Version version=new Version(); version.title="第一章";
            version.content="她摇了摇头。她真的想不起来，不是回避，不是假装。";
            version.summary="她否认记得那个画面。";
            version.review=new Review(true,List.of(),false,false,false,List.of());
            version.reviewRevision=stored.revision; version.reviewPolicyVersion=ReviewPolicy.VERSION;
            ReviewIssue style=new ReviewIssue("当前正文","后一句重复解释同一结论",
                    "原文：“她真的想不起来，不是回避，不是假装。”","删除这句重复解释","建议优化");
            version.styleReview=new Review(true,List.of(style.text()),false,false,false,List.of(style));
            version.styleReviewPolicyVersion=StyleReviewPolicy.VERSION;
            chapter.versions.add(version); stored.artifacts.add(chapter); return stored;
        });
        Artifact chapter=n.artifacts.getLast(); String originalVersionId=chapter.latest().id; long originalRevision=n.revision;

        Task task=tasks.submitStylePolish(id,chapter.id,Novel.uid(),n.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->!List.of(TaskStatus.QUEUED,TaskStatus.RUNNING)
                .contains(tasks.find(repository.get(id),task.id).status));

        Novel after=repository.get(id); Task result=tasks.find(after,task.id);
        assertThat(result.status).isEqualTo(TaskStatus.FAILED);
        assertThat(result.error).contains("文风复查模拟失败");
        assertThat(after.revision).isEqualTo(originalRevision);
        assertThat(rules.artifact(after,chapter.id).latest().id).isEqualTo(originalVersionId);
        assertThat(rules.artifact(after,chapter.id).versions).hasSize(1);
    }
}
