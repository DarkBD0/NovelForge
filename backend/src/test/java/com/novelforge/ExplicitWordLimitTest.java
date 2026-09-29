package com.novelforge;

import com.novelforge.novel.ExplicitWordLimit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ExplicitWordLimitTest {
    private final ExplicitWordLimit limits=new ExplicitWordLimit();
    @Test void readsExplicitMaximumButNotApproximateTarget() {
        assertThat(limits.from("正文约2800字，最多约3200字")).hasValue(3200);
        assertThat(limits.from("控制在约2800字")).isEmpty();
        assertThat(limits.from("不超过 3500 字，上限为4000字")).hasValue(3500);
    }
}
