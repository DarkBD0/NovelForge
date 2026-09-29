package com.novelforge.novel;

import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

@Component
public class WordCounter {
    // Apostrophes join English words; hyphens separate them. Digits themselves never count.
    private static final Pattern ENGLISH = Pattern.compile("[A-Za-z]+(?:['’][A-Za-z]+)*");
    public long count(String body) {
        if (body == null) return 0;
        long han = body.codePoints().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN).count();
        return han + ENGLISH.matcher(body).results().count();
    }
    public long approvedWords(Novel novel) {
        return novel.artifacts.stream().filter(a -> a.kind == Novel.Kind.CHAPTER && a.approved() != null)
                .mapToLong(a -> count(a.approved().content)).sum();
    }
    public long defaultMax(long target) { return target * 11 / 10; }
}
