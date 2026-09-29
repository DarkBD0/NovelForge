package com.novelforge.generation;

import com.novelforge.novel.Novel.Action;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceSnapshotFactoryTest {
    private final SourceSnapshotFactory factory = new SourceSnapshotFactory();

    @Test
    void restoresTheExactFrozenContextAndSources() {
        ContextAssembler.Context context = new ContextAssembler.Context("{\"chapter\":3}", List.of("v1", "v2"), 3, 2);
        var snapshot = factory.capture("task-1", 7, Action.CHAPTER, "chapter-3", context);

        ContextAssembler.Context restored = factory.restore(snapshot);

        assertThat(restored).isEqualTo(context);
        assertThat(snapshot.contextHash).hasSize(64);
        assertThat(snapshot.novelRevision).isEqualTo(7);
        assertThat(snapshot.taskId).isEqualTo("task-1");
    }

    @Test
    void rejectsAChangedSnapshotBeforeAnyModelCall() {
        var snapshot = factory.capture("task-1", 7, Action.CHAPTER, null,
                new ContextAssembler.Context("{\"chapter\":3}", List.of("v1"), 3, 2));
        snapshot.contextJson = "{\"chapter\":4}";

        assertThatThrownBy(() -> factory.restore(snapshot))
                .hasMessageContaining("来源快照校验失败");
    }
}
