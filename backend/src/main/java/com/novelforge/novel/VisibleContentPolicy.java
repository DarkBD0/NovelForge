package com.novelforge.novel;

import com.novelforge.shared.Problem;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rejects implementation identifiers in author-facing prose without banning normal foreign-language fiction. */
public final class VisibleContentPolicy {
    private static final Pattern INTERNAL=Pattern.compile(
            "(?i)candidate\\.(?:facts|content|summary|plan)|context\\.(?:authoritativeReferences|revisionTarget)|"
            + "\\b(?:finalBatch|prepareNextAfterChapter|sourceVersionIds|reviewRevision|basedOnRevision)\\b|"
            + "\\b(?:character|foreshadow|event|timeline|relationship|world|location|item)_[a-z0-9_]+\\b");
    private VisibleContentPolicy() {}

    public static void validate(String area, String text) {
        Matcher matcher=INTERNAL.matcher(text);
        if (matcher.find()) throw new Problem(409,area+"包含系统内部标识“"+matcher.group()+"”；本次内容未保存。请改成读者能理解的自然语言");
    }
}
