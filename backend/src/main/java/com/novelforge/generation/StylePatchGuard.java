package com.novelforge.generation;

import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Version;
import com.novelforge.shared.Problem;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Restricts the one automatic prose pass to small, exact, content-only reductions. */
public final class StylePatchGuard {
    public static final String INSTRUCTION_MARKER="【系统安全文风修改】";
    private static final Pattern PROTECTED_TOKEN=Pattern.compile("(?:\\d+(?:[.:：/-]\\d+)*)|(?:[零〇一二三四五六七八九十百千万亿两]+(?:公里|年|月|日|点|时|分|秒|章|个|次|天|岁|元|米))|(?:[A-Za-z][A-Za-z0-9_-]*)");

    private StylePatchGuard() {}

    static ModelGateway.Generated apply(Version base, RewritePatch patch, Kind kind) {
        if (kind!=Kind.CHAPTER) throw error("文风自动修改只适用于章节正文");
        if (patch==null || patch.operations()==null || patch.operations().isEmpty())
            throw error("模型没有返回可应用的局部修改");
        if (patch.operations().size()>8) throw error("单轮最多应用八处局部修改");
        int affected=0;
        for (RewritePatch.Operation operation:patch.operations()) {
            if (operation==null || !"REPLACE_TEXT".equals(operation.op()) || !"content".equals(operation.field()))
                throw error("文风自动修改只能精确替换或删除正文片段，不能修改标题、摘要、档案或章节规划");
            String oldText=operation.oldText(),newText=operation.newText();
            if (oldText==null || oldText.isBlank() || newText==null)
                throw error("文风修改缺少可核对的原文或替换文字");
            if (oldText.length()>500) throw error("单处修改范围过大，已拒绝自动应用");
            affected+=oldText.length();
            if (affected>2000) throw error("本轮修改总范围过大，已拒绝自动应用");
            if (!newText.isBlank())
                throw error("自动文风修改只允许删除明确赘句；任何改写都交给作者决定");
            if (newText.isBlank() && overlapsRecordedFacts(base,oldText))
                throw error("待删除原句与摘要或档案事实重合，不能作为纯文风赘句自动删除");
            if (!tokens(oldText).equals(tokens(newText)))
                throw error("修改会改变数字、时间或英文专名，已转为作者建议");
        }
        ModelGateway.Generated result=RewritePatchApplier.apply(base,patch,kind);
        if (!base.title.equals(result.title()) || !base.summary.equals(result.summary())
                || !base.facts.equals(result.facts()) || base.plan!=result.plan())
            throw error("文风修改越过正文边界，已拒绝自动应用");
        return result;
    }

    private static List<String> tokens(String text) {
        var result=new ArrayList<String>(); Matcher matcher=PROTECTED_TOKEN.matcher(text);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private static boolean overlapsRecordedFacts(Version base,String oldText) {
        String source=semantic(oldText);
        if (source.length()<2) return false;
        StringBuilder recorded=new StringBuilder(base.summary==null?"":base.summary);
        if (base.facts!=null) base.facts.forEach(fact->recorded.append('\n').append(fact.detail()));
        String target=semantic(recorded.toString());
        int width=source.length()<10?2:4;
        for (int i=0;i<=source.length()-width;i++) if (target.contains(source.substring(i,i+width))) return true;
        return false;
    }

    private static String semantic(String text) {
        return text==null?"":text.replaceAll("[^\\p{IsHan}A-Za-z0-9]","");
    }

    private static Problem error(String detail) {
        return new Problem(502,"文风安全检查未通过："+detail+"；原版本保持不变，可查看建议后手动处理");
    }
}
