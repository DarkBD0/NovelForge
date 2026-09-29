package com.novelforge.generation;

import com.novelforge.novel.Novel.Fact;
import com.novelforge.novel.Novel.Plan;
import java.util.List;

/** Small, deterministic model output for revising an existing artifact. */
public record RewritePatch(List<Operation> operations) {
    public record Operation(String op, String field, String key, String oldText, String newText,
                            String value, Fact fact, List<Fact> facts, Plan plan) {}
}
