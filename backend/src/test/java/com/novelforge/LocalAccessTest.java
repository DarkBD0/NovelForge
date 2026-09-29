package com.novelforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:access;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class LocalAccessTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired NovelRepository repository;
    private final String body="{\"title\":\"本机作品\",\"synopsis\":\"只有标题和简介也可以开始\"}";
    @Test void canCreateWithOnlyTitleAndSynopsis() throws Exception {
        mvc.perform(post("/api/novels").header("X-NovelForge-Request","1").contentType("application/json").content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.novel.targetWords").value(100000))
            .andExpect(jsonPath("$.novel.autoStyleEnabled").value(true))
            .andExpect(jsonPath("$.novel.sourceSnapshots").doesNotExist())
            .andExpect(jsonPath("$.novel.agentRuns").doesNotExist())
            .andExpect(jsonPath("$.novel.shadowReviews").doesNotExist())
            .andExpect(jsonPath("$.shadowReviewSummary.totalReports").value(0));
    }
    @Test void canToggleAutomaticStyleWithoutInvalidatingContentRevision() throws Exception {
        var created=mvc.perform(post("/api/novels").header("X-NovelForge-Request","1")
                        .contentType("application/json").content(body)).andReturn();
        var novel=mapper.readTree(created.getResponse().getContentAsByteArray()).path("novel");
        String id=novel.path("id").asText(); long revision=novel.path("revision").asLong();

        mvc.perform(post("/api/novels/{id}/auto-style-settings",id).header("X-NovelForge-Request","1")
                        .contentType("application/json")
                        .content("{\"enabled\":false,\"revision\":"+revision+"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.novel.autoStyleEnabled").value(false))
                .andExpect(jsonPath("$.novel.revision").value(revision));
    }
    @Test void canChangeTargetAndUpperLimitTogether() throws Exception {
        var created=mvc.perform(post("/api/novels").header("X-NovelForge-Request","1")
                        .contentType("application/json")
                        .content("{\"title\":\"可调整篇幅\",\"synopsis\":\"用于验证字数设置\",\"targetWords\":1000}"))
                .andExpect(status().isCreated()).andReturn();
        var novel=mapper.readTree(created.getResponse().getContentAsByteArray()).path("novel");
        String id=novel.path("id").asText();
        long revision=novel.path("revision").asLong();

        mvc.perform(post("/api/novels/{id}/budget-adjustments",id).header("X-NovelForge-Request","1")
                        .contentType("application/json")
                        .content("{\"targetWords\":1200,\"approvedMaxWords\":1500,\"reason\":\"剧情篇幅调整\",\"revision\":"+revision+"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.novel.targetWords").value(1200))
                .andExpect(jsonPath("$.novel.approvedMaxWords").value(1500))
                .andExpect(jsonPath("$.novel.status").value("WRITING"));
    }
    @Test void blocksCrossOriginAndMissingWriteHeader() throws Exception {
        mvc.perform(post("/api/novels").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/novels").header("X-NovelForge-Request","1").header("Origin","https://evil.example").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/novels").header("X-NovelForge-Request","1").header("Origin","http://localhost:9999").contentType("application/json").content(body)).andExpect(status().isForbidden());
    }
    @Test void blocksDnsRebindingAndRemoteAccess() throws Exception {
        mvc.perform(get("/api/novels").with(r->{r.setServerName("evil.example");return r;})).andExpect(status().isForbidden());
        mvc.perform(get("/api/novels").with(r->{r.setRemoteAddr("192.168.1.10");return r;})).andExpect(status().isForbidden());
    }
    @Test void rejectsBadPayloadAndDoesNotReturnStackTraces() throws Exception {
        mvc.perform(post("/api/novels").header("X-NovelForge-Request","1").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.trace").doesNotExist());
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(header().string("X-Frame-Options","DENY"));
    }
    @Test void servesReaderFacingReviewGuidance() throws Exception {
        mvc.perform(get("/app.js")).andExpect(status().isOk()).andExpect(content().string(allOf(
                containsString("humanizeIssue"), containsString("copyReviewButton"),
                containsString("factTypeName"), containsString("friendlyError"), containsString("renderFacts"),
                containsString("factEditorRow"), containsString("legacyIssueText"), containsString("compactLegacyIssue"),
                containsString("renderWordSettings(n)"), containsString("name=\"target\""),
                containsString("targetWords:Number(data.get('target'))"),
                containsString("styleReviewButton"), containsString("copyStyleReviewButton"),
                containsString("autoFixButton"), containsString("content-fix-tasks"),
                containsString("stylePolishButton"), containsString("style-polish-tasks"),
                containsString("autoStyleToggle"), containsString("auto-style-settings"),
                containsString("styleReviewIssueText"), containsString("STYLE_REVIEW"),
                containsString("renderShadowExperiments"), containsString("data-shadow-feedback"),
                containsString("shadowReviewSummary"), containsString("averageModelDurationMillis"),
                containsString("shadowPollUntil"), containsString("professionalRunning"),
                not(containsString("JSON.stringify(v.facts")))));
        mvc.perform(get("/review-text.js")).andExpect(status().isOk()).andExpect(content().string(allOf(
                containsString("One replacement pass"), containsString("compactLegacyIssue"))));
    }

    @Test void authorCanEvaluateCompletedProfessionalReportWithoutChangingNovelRevision() throws Exception {
        var created=mvc.perform(post("/api/novels").header("X-NovelForge-Request","1")
                        .contentType("application/json").content(body)).andReturn();
        var novelJson=mapper.readTree(created.getResponse().getContentAsByteArray()).path("novel");
        String novelId=novelJson.path("id").asText();
        long revision=novelJson.path("revision").asLong();
        String reportId=repository.update(novelId,novel->{
            Artifact artifact=new Artifact(); artifact.kind=Kind.CHAPTER; artifact.chapterNumber=1;
            Version version=new Version(); version.title="第一章"; version.content="正文"; version.summary="摘要";
            version.review=new Review(true,List.of(),false,false,false,List.of());
            artifact.versions.add(version); novel.artifacts.add(artifact);
            ShadowReview report=new ShadowReview(); report.artifactId=artifact.id; report.versionId=version.id;
            report.checker="CONTINUITY"; report.status=ShadowReviewStatus.SUCCEEDED;
            report.review=new Review(true,List.of(),false,false,false,List.of());
            report.modelStartedAt="2026-01-01T00:00:00Z"; report.finishedAt="2026-01-01T00:00:01Z";
            novel.shadowReviews.add(report); return report.id;
        });

        mvc.perform(get("/api/novels/{id}/shadow-review-summary",novelId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalReports").value(1))
                .andExpect(jsonPath("$.modelCalls").value(1));
        mvc.perform(post("/api/novels/{id}/shadow-reviews/{reportId}/feedback",novelId,reportId)
                        .header("X-NovelForge-Request","1").contentType("application/json")
                        .content("{\"decision\":\"USEFUL\",\"note\":\"发现了有效冲突\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.novel.revision").value(revision))
                .andExpect(jsonPath("$.novel.shadowReviews").doesNotExist())
                .andExpect(jsonPath("$.shadowReviewSummary.useful").value(1))
                .andExpect(jsonPath("$.shadowReviewSummary.reports[0].authorDecision").value("USEFUL"));
    }
}
