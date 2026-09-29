package com.novelforge.novel;

import com.novelforge.novel.Novel.Fact;
import com.novelforge.shared.Problem;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.novelforge.shared.Problem.require;

/** Current rules for facts that are newly written or explicitly touched in a character artifact. */
public final class CharacterFactPolicy {
    private static final Pattern STORY_ORDER=Pattern.compile("第一次|初次|首次|随后|后来|接着|先于|早于|晚于|第[一二三四五六七八九十百0-9]+章");
    private CharacterFactPolicy() {}

    public static void validate(Fact fact) {
        require(List.of("WORLD","CHARACTER").contains(fact.type()), "人物设定的档案只能记录人物静态设定或世界规则");
        Matcher order=STORY_ORDER.matcher(fact.detail());
        if (order.find()) throw new Problem(409,"人物设定中的" + readableFact(fact.detail()) + "仍包含剧情顺序词“" + order.group()
                + "”；本次修订未保存。请删除该顺序表述，只保留人物身份、性格、动机、能力或稳定关系");
    }

    private static String readableFact(String detail) {
        String clean=detail.replaceAll("(?i)\\b(?:character|foreshadow|event|relationship|world|location|item)_[a-z0-9_]+\\b","相关人物")
                .replaceAll("\\s+"," ").trim();
        String hint=clean.split("[，。；：]",2)[0];
        if (hint.length()<2 || hint.length()>16) hint=clean.substring(0,Math.min(clean.length(),24))+(clean.length()>24?"…":"");
        return "档案条目“"+hint+"”";
    }
}
