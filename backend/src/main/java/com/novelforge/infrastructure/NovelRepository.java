package com.novelforge.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.novel.Novel;
import com.novelforge.shared.Problem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.function.Function;

@Repository
public class NovelRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    public NovelRepository(JdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate transactions) {
        this.jdbc = jdbc; this.mapper = mapper; this.transactions = transactions;
    }
    public void insert(Novel novel) {
        jdbc.update("INSERT INTO novels(id, document, created_at) VALUES(?, ?, ?)", novel.id, encode(novel), novel.createdAt);
    }
    public List<Novel> list() {
        return jdbc.query("SELECT document FROM novels ORDER BY created_at DESC", (rs, i) -> decode(rs.getString(1)));
    }
    public Novel get(String id) { return load(id, false); }
    public <T> T update(String id, Function<Novel, T> mutation) {
        return transactions.execute(status -> {
            Novel novel = load(id, true);
            T result = mutation.apply(novel);
            jdbc.update("UPDATE novels SET document=? WHERE id=?", encode(novel), id);
            return result;
        });
    }
    private Novel load(String id, boolean lock) {
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
