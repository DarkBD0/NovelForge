package com.novelforge.generation;

import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.SourceSnapshot;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;

import static com.novelforge.shared.Problem.require;

/** Captures and verifies the exact context used by one generation task. */
@Component
public class SourceSnapshotFactory {
    public SourceSnapshot capture(String taskId, long novelRevision, Action action, String artifactId,
                                  ContextAssembler.Context context) {
        require(context != null && context.json() != null, "任务上下文不能为空");
        SourceSnapshot snapshot = new SourceSnapshot();
        snapshot.taskId = taskId;
        snapshot.novelRevision = novelRevision;
        snapshot.action = action;
        snapshot.artifactId = artifactId;
        snapshot.contextJson = context.json();
        snapshot.contextHash = hash(context.json());
        snapshot.sourceVersionIds = new ArrayList<>(context.sourceVersions());
        snapshot.chapterNumber = context.chapterNumber();
        snapshot.batchNumber = context.batchNumber();
        return snapshot;
    }

    public ContextAssembler.Context restore(SourceSnapshot snapshot) {
        require(snapshot != null, "任务来源快照不存在");
        require(snapshot.contextJson != null && !snapshot.contextJson.isBlank(), "任务来源快照缺少上下文");
        require(snapshot.contextHash != null && snapshot.contextHash.equals(hash(snapshot.contextJson)),
                "任务来源快照校验失败，已停止调用模型");
        return new ContextAssembler.Context(snapshot.contextJson,
                snapshot.sourceVersionIds == null ? java.util.List.of() : java.util.List.copyOf(snapshot.sourceVersionIds),
                snapshot.chapterNumber, snapshot.batchNumber);
    }

    public String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 Java 环境不支持 SHA-256", e);
        }
    }
}
