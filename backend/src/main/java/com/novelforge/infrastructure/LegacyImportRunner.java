package com.novelforge.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel;
import com.novelforge.novel.WordCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One-shot, atomic import from the legacy H2 document table into a new relational database. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name="novelforge.storage.import-on-startup",havingValue="true")
public class LegacyImportRunner implements CommandLineRunner {
    private static final Logger log=LoggerFactory.getLogger(LegacyImportRunner.class);
    private final String sourceUrl;
    private final String sourceUsername;
    private final String sourcePassword;
    private final DataSource targetDataSource;
    private final JdbcTemplate targetJdbc;
    private final NovelRepository repository;
    private final NormalizedNovelProjection normalized;
    private final ObjectMapper mapper;
    private final WordCounter words;
    private final TransactionTemplate transactions;

    public LegacyImportRunner(@Value("${novelforge.storage.legacy-import-url:}") String sourceUrl,
                              @Value("${novelforge.storage.legacy-import-username:sa}") String sourceUsername,
                              @Value("${novelforge.storage.legacy-import-password:}") String sourcePassword,
                              DataSource targetDataSource,JdbcTemplate targetJdbc,NovelRepository repository,
                              NormalizedNovelProjection normalized,ObjectMapper mapper,WordCounter words,
                              TransactionTemplate transactions) {
        this.sourceUrl=sourceUrl; this.sourceUsername=sourceUsername; this.sourcePassword=sourcePassword;
        this.targetDataSource=targetDataSource; this.targetJdbc=targetJdbc; this.repository=repository;
        this.normalized=normalized; this.mapper=mapper; this.words=words; this.transactions=transactions;
    }

    @Override public void run(String... args) throws Exception {
        if(sourceUrl==null || sourceUrl.isBlank()) throw new IllegalStateException("已启用旧库导入，但未配置 NOVELFORGE_LEGACY_IMPORT_URL");
        if(!sourceUrl.startsWith("jdbc:h2:file:")) throw new IllegalStateException("旧库导入只允许读取本机 H2 文件数据库");
        String targetUrl;
        try(var connection=targetDataSource.getConnection()) { targetUrl=connection.getMetaData().getURL(); }
        if(targetUrl.startsWith("jdbc:h2:")) throw new IllegalStateException("旧库导入目标必须是新建的 MySQL 数据库，不能写回 H2");
        if(!normalized.enabled()) throw new IllegalStateException("迁移时必须启用拆分存储投影");

        DriverManagerDataSource sourceDataSource=new DriverManagerDataSource(sourceUrl,sourceUsername,sourcePassword);
        JdbcTemplate sourceJdbc=new JdbcTemplate(sourceDataSource);
        List<SourceRow> sourceRows=sourceJdbc.query("SELECT id,document FROM novels ORDER BY id",
                (rs,index)->new SourceRow(rs.getString(1),rs.getString(2)));
        if(sourceRows.isEmpty()) throw new IllegalStateException("旧 H2 中没有可迁移的小说，已停止以避免初始化错误数据库");
        String fingerprint=sourceFingerprint(sourceRows);
        Integer completed=targetJdbc.queryForObject("SELECT COUNT(*) FROM storage_migration_run WHERE source_fingerprint=? AND status='SUCCEEDED'",Integer.class,fingerprint);
        if(completed!=null && completed>0) {
            log.info("Legacy storage import already completed: fingerprint={} novels={}",fingerprint.substring(0,12),sourceRows.size());
            return;
        }
        Integer existingLegacy=targetJdbc.queryForObject("SELECT COUNT(*) FROM novels",Integer.class);
        Integer existingProjects=targetJdbc.queryForObject("SELECT COUNT(*) FROM novel_project",Integer.class);
        if((existingLegacy!=null && existingLegacy>0) || (existingProjects!=null && existingProjects>0)) {
            throw new IllegalStateException("目标数据库已有数据且没有匹配的成功迁移记录，拒绝覆盖；请使用新的 NovelForge 数据库");
        }

        List<Novel> novels=new ArrayList<>();
        for(SourceRow row:sourceRows) {
            Novel novel=mapper.readValue(row.document(),Novel.class);
            if(!row.id().equals(novel.id)) throw new IllegalStateException("旧库小说编号与文档内容不一致："+row.id());
            requireNoRunningWork(novel);
            novels.add(novel);
        }
        String runId=UUID.randomUUID().toString(); String started=Instant.now().toString();
        transactions.executeWithoutResult(status->{
            for(Novel novel:novels) repository.insert(novel);
            MigrationTotals totals=totals(novels);
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("sourceFingerprint",fingerprint); report.put("novelCount",totals.novels());
            report.put("artifactCount",totals.artifacts()); report.put("versionCount",totals.versions());
            report.put("approvalCount",totals.approvals()); report.put("taskCount",totals.tasks());
            report.put("conversationCount",totals.conversations()); report.put("approvedWordCount",totals.approvedWords());
            report.put("novels",novels.stream().map(n->Map.of("id",n.id,"title",n.title,"revision",n.revision,
                    "approvedWords",words.approvedWords(n),"projection",normalized.verify(n).counts())).toList());
            targetJdbc.update("""
                    INSERT INTO storage_migration_run(id,source_kind,source_fingerprint,status,novel_count,artifact_count,
                      version_count,approval_count,task_count,conversation_count,approved_word_count,report_json,started_at,finished_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,runId,"H2_DOCUMENT",fingerprint,"SUCCEEDED",totals.novels(),totals.artifacts(),totals.versions(),
                    totals.approvals(),totals.tasks(),totals.conversations(),totals.approvedWords(),json(report),started,Instant.now().toString());
        });
        log.info("Legacy storage import completed: fingerprint={} novels={} target=MySQL",fingerprint.substring(0,12),novels.size());
    }

    private void requireNoRunningWork(Novel novel) {
        boolean active=novel.tasks.stream().anyMatch(t->t.status==Novel.TaskStatus.QUEUED || t.status==Novel.TaskStatus.RUNNING)
                || novel.agentRuns.stream().anyMatch(r->r.status==Novel.AgentRunStatus.RUNNING)
                || novel.shadowReviews.stream().anyMatch(r->r.status==Novel.ShadowReviewStatus.RUNNING)
                || novel.outlinePipelines.stream().anyMatch(p->p.status==Novel.OutlinePipelineStatus.RUNNING)
                || novel.conversationSessions.stream().flatMap(s->s.turns.stream()).anyMatch(t->t.status==Novel.ConversationTurnStatus.RUNNING);
        if(active) throw new IllegalStateException("小说“"+novel.title+"”仍有运行中任务，迁移已停止且未写入目标库");
    }

    private MigrationTotals totals(List<Novel> novels) {
        return new MigrationTotals(novels.size(),novels.stream().mapToLong(n->n.artifacts.size()).sum(),
                novels.stream().flatMap(n->n.artifacts.stream()).mapToLong(a->a.versions.size()).sum(),
                novels.stream().mapToLong(n->n.approvals.size()).sum(),novels.stream().mapToLong(n->n.tasks.size()).sum(),
                novels.stream().mapToLong(n->n.conversationSessions.size()).sum(),novels.stream().mapToLong(words::approvedWords).sum());
    }
    private String sourceFingerprint(List<SourceRow> rows) {
        StringBuilder value=new StringBuilder();
        for(SourceRow row:rows) {
            try {
                JsonNode normalized=mapper.readTree(row.document());
                value.append(row.id()).append(':').append(hash(mapper.writeValueAsString(normalized))).append('\n');
            } catch(Exception e) { throw new IllegalStateException("无法计算旧库内容指纹："+row.id(),e); }
        }
        return hash(value.toString());
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(Exception e) { throw new IllegalStateException("无法保存迁移报告",e); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private record SourceRow(String id,String document) {}
    private record MigrationTotals(int novels,long artifacts,long versions,long approvals,long tasks,long conversations,long approvedWords) {}
}
