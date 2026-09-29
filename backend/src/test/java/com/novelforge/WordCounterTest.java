package com.novelforge;

import com.novelforge.novel.WordCounter;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WordCounterTest {
    private final WordCounter counter = new WordCounter();
    @Test void countsHanAndEnglishButNotDigitsPunctuationOrSpaces() {
        assertThat(counter.count("你好，世界！Hello world 2026，42。" )).isEqualTo(6);
        assertThat(counter.count("don't mother-in-law GPT4 123" )).isEqualTo(5);
        assertThat(counter.count("𠀀文" )).isEqualTo(2);
        assertThat(counter.count(null)).isZero();
    }
    @Test void maximumIsFlooredTenPercent() {
        assertThat(counter.defaultMax(101)).isEqualTo(111);
    }
}
