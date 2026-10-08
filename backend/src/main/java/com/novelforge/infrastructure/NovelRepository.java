package com.novelforge.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel;
import com.novelforge.shared.Problem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.function.Function;

@Repository
public class NovelRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final NormalizedNovelProjection normalized;
    private final boolean normalizedPrimary;

    @Autowired
    public NovelRepository(JdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate transactions,
                           ObjectProvider<NormalizedNovelProjection> normalized,
                           @Value("${novelforge.storage.mode:legacy}") String storageMode) {
        this.jdbc = jdbc; this.mapper = mapper; this.transactions = transactions;
        this.normalized = normalized.getIfAvailable();
        this.normalizedPrimary = "normalized".equalsIgnoreCase(storageMode);
        if (normalizedPrimary && (this.normalized == null || !this.normalized.enabled())) {
            throw new IllegalStateException("拆分存储主读模式要求启用关系投影");
        }
    }

    /** Kept for isolated persistence tests and offline migration utilities. */
    public NovelRepository(JdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate transactions) {
        this.jdbc = jdbc; this.mapper = mapper; this.transactions = transactions; this.normalized = null; this.normalizedPrimary = false;
    }
    public void insert(Novel novel) {
        transactions.executeWithoutResult(status -> {
            if (normalizedPrimary) normalized.replace(novel);
            else {
                jdbc.update("INSERT INTO novels(id, document, created_at) VALUES(?, ?, ?)", novel.id, encode(novel), novel.createdAt);
                if (normalized != null) normalized.replace(novel);
            }
        });
    }
    public List<Novel> list() {
        if (normalizedPrimary) return normalized.list();
        return jdbc.query("SELECT document FROM novels ORDER BY created_at DESC", (rs, i) -> decode(rs.getString(1)));
    }
    public Novel get(String id) { return load(id, false); }
    public <T> T update(String id, Function<Novel, T> mutation) {
        return transactions.execute(status -> {
            Novel novel = load(id, true);
            T result = mutation.apply(novel);
            if (normalizedPrimary) normalized.replace(novel);
            else {
                jdbc.update("UPDATE novels SET document=? WHERE id=?", encode(novel), id);
                if (normalized != null) normalized.replace(novel);
            }
            return result;
        });
    }
    private Novel load(String id, boolean lock) {
        if (normalizedPrimary) return normalized.load(id,lock);
        var rows = jdbc.query("SELECT document FROM novels WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, i) -> decode(rs.getString(1)), id);
        if (rows.isEmpty()) throw new Problem(404, "小说不存在");
        return rows.getFirst();
    }
    private String encode(Novel novel) {
        try { return mapper.writeValueAsString(novel); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法序列化小说", e); }
    }
    private Novel decode(String json) {
        try { return mapper.readValue(json, Novel.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法读取小说数据，未覆盖原记录", e); }
    }
}
