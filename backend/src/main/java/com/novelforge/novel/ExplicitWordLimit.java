package com.novelforge.novel;

import org.springframework.stereotype.Component;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/** Extracts only an explicit hard limit; approximate plan targets remain advisory. */
@Component
public class ExplicitWordLimit {
    private static final Pattern LIMIT=Pattern.compile("(?:最多|不超过|上限(?:为|是)?)[^0-9]{0,6}([0-9]{2,9})\\s*字");
    public OptionalLong from(String instructions) {
        if (instructions==null) return OptionalLong.empty();
        var matcher=LIMIT.matcher(instructions); long lowest=Long.MAX_VALUE;
        while (matcher.find()) lowest=Math.min(lowest,Long.parseLong(matcher.group(1)));
        return lowest==Long.MAX_VALUE?OptionalLong.empty():OptionalLong.of(lowest);
    }
}
