package com.novelforge;

import com.novelforge.generation.ModelGateway;
import com.novelforge.generation.ReviewPolicy;
import com.novelforge.generation.StyleReviewPolicy;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.*;
import com.novelforge.task.TaskService;
import com.novelforge.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:auto-style;DB_CLOSE_DELAY=-1",
        "novelforge.experiments.continuity-shadow-enabled=false",
        "novelforge.experiments.plot-foreshadow-shadow-enabled=false"
})
class AutoStylePipelineIntegrationTest {
    @Autowired NovelRepository repository;
    @Autowired WorkflowService workflow;
    @Autowired TaskService tasks;
    @MockitoBean ModelGateway model;

    @BeforeEach void modelDefaults() {
        when(model.ready()).thenReturn(true);
        when(model.mode()).thenReturn("http");
        when(model.review(any(),any())).thenReturn(new Review(true,List.of(),false,false,false,List.of()));
        when(model.styleReview(any(),any())).thenAnswer(invocation->{
            ModelGateway.Generated candidate=invocation.getArgument(1);
            if (candidate.content().contains("[演示文风问题]")) {
                ReviewIssue issue=new ReviewIssue("当前章节正文","末尾标记属于重复解释",
                        "原文：“[演示文风问题]”","删除这句重复解释","建议优化");
                return new Review(true,List.of(issue.text()),false,false,false,List.of(issue));
            }
            return new Review(true,List.of(),false,false,false,List.of());
        });
    }

    @Test void enabledSettingRunsOneBoundedStylePipelineAndStops() {
        Novel novel=seedPendingChapter(true);
        Artifact chapter=novel.artifacts.getFirst();

        tasks.submit(novel.id,Action.REVIEW,chapter.id,"",Novel.uid(),novel.revision);
        awaitPipeline(novel.id,3);

        Novel after=repository.get(novel.id); Artifact polished=after.artifacts.getFirst(); Version latest=polished.latest();
        assertThat(after.tasks).hasSize(3);
        assertThat(after.tasks).extracting(task->task.automationKind)
                .containsExactly("",TaskService.AUTO_STYLE_CHECK,TaskService.STYLE_POLISH);
        assertThat(after.tasks).allMatch(task->task.status==TaskStatus.SUCCEEDED);
        assertThat(polished.versions).hasSize(2);
        assertThat(latest.content).isEqualTo("正文。");
        assertThat(latest.stylePolishRound).isEqualTo(1);
        assertThat(latest.review.passed()).isTrue();
        assertThat(latest.reviewPolicyVersion).isEqualTo(ReviewPolicy.VERSION);
        assertThat(latest.styleReview.issueDetails()).isEmpty();
        assertThat(latest.styleReviewPolicyVersion).isEqualTo(StyleReviewPolicy.VERSION);
        verify(model,times(2)).review(any(),any());
        verify(model,times(2)).styleReview(any(),any());
    }

    @Test void disabledOrLegacySettingDoesNotQueueExtraCalls() {
        Novel novel=seedPendingChapter(false);
        Artifact chapter=novel.artifacts.getFirst();

        Task initial=tasks.submit(novel.id,Action.REVIEW,chapter.id,"",Novel.uid(),novel.revision);
        await().atMost(Duration.ofSeconds(10)).until(()->tasks.find(repository.get(novel.id),initial.id).status==TaskStatus.SUCCEEDED);

        Novel after=repository.get(novel.id);
        assertThat(after.tasks).hasSize(1);
        assertThat(after.artifacts.getFirst().versions).hasSize(1);
        verify(model,times(1)).review(any(),any());
        verify(model,never()).styleReview(any(),any());
    }

    private Novel seedPendingChapter(boolean enabled) {
        Novel novel=workflow.create("自动文风测试","验证自动文风单轮流水线",1000,"保持事实不变");
        return repository.update(novel.id,stored->{
            stored.autoStyleEnabled=enabled?Boolean.TRUE:null;
            Artifact chapter=new Artifact(); chapter.kind=Kind.CHAPTER; chapter.chapterNumber=1;
            Version version=new Version(); version.title="第一章"; version.content="正文。[演示文风问题]";
            version.summary="正文事件摘要"; version.source="MODEL";
            chapter.versions.add(version); stored.artifacts.add(chapter); return stored;
        });
    }

    private void awaitPipeline(String novelId,int expectedTasks) {
        await().atMost(Duration.ofSeconds(15)).until(()->{
            Novel current=repository.get(novelId);
            return current.tasks.size()==expectedTasks
                    && current.tasks.stream().noneMatch(task->List.of(TaskStatus.QUEUED,TaskStatus.RUNNING).contains(task.status));
        });
    }
}
