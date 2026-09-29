package com.novelforge.generation;

import com.novelforge.novel.Novel.Fact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Plan;
import com.novelforge.novel.Novel.Version;
import com.novelforge.novel.CharacterFactPolicy;
import com.novelforge.shared.Problem;
import java.util.ArrayList;
import java.util.List;

/** Applies only exact, locally verifiable changes. It never guesses a fuzzy text match. */
final class RewritePatchApplier {
    private RewritePatchApplier() {}

    static ModelGateway.Generated apply(Version base, RewritePatch patch, Kind artifactKind) {
        if (base==null) throw error("修订目标没有可用版本");
        String title=base.title, content=base.content, summary=base.summary;
        List<Fact> facts=new ArrayList<>(base.facts==null ? List.of() : base.facts);
        Plan plan=base.plan;
        for (RewritePatch.Operation operation : patch.operations()) {
            switch (operation.op()) {
                case "REPLACE_TEXT" -> {
                    String current=switch(operation.field()) {
                        case "title" -> title; case "content" -> content; case "summary" -> summary;
                        default -> throw error("REPLACE_TEXT 的 field 只能是 title、content 或 summary");
                    };
                    int first=current.indexOf(operation.oldText());
                    if (first<0) throw error("模型指定的旧文字在当前"+fieldName(operation.field())+"中不存在");
                    if (current.indexOf(operation.oldText(),first+operation.oldText().length())>=0)
                        throw error("模型指定的旧文字在当前"+fieldName(operation.field())+"中出现多次，无法安全定位");
                    String changed=current.substring(0,first)+operation.newText()+current.substring(first+operation.oldText().length());
                    switch(operation.field()) {
                        case "title" -> title=changed; case "content" -> content=changed; case "summary" -> summary=changed;
                    }
                }
                case "SET_FIELD" -> {
                    if ("title".equals(operation.field())) title=operation.value();
                    else if ("summary".equals(operation.field())) summary=operation.value();
                    else throw error("SET_FIELD 只能设置 title 或 summary；正文必须使用精确文字替换");
                }
                case "SET_CONTENT" -> {
                    if (artifactKind!=Kind.CHAPTER) throw error("只有章节正文允许整章重写");
                    content=operation.value();
                }
                case "UPSERT_FACT" -> {
                    Fact fact=operation.fact();
                    if (artifactKind==Kind.CHARACTERS) CharacterFactPolicy.validate(fact);
                    int index=indexOf(facts,fact.key());
                    if (index<0) facts.add(fact); else facts.set(index,fact);
                }
                case "DELETE_FACT" -> {
                    int index=indexOf(facts,operation.key());
                    if (index<0) throw error("模型要求删除的档案条目不存在");
                    facts.remove(index);
                }
                case "REPLACE_FACTS" -> facts=new ArrayList<>(operation.facts());
                case "SET_PLAN" -> {
                    // Compatible providers sometimes echo the read-only chapter plan.
                    // Ignore it safely: only a PLAN artifact may change planning data.
                    if (artifactKind==Kind.PLAN) plan=operation.plan();
                }
                default -> throw error("未知修订操作");
            }
        }
        return new ModelGateway.Generated(title,content,summary,List.copyOf(facts),plan);
    }

    private static int indexOf(List<Fact> facts,String key) {
        for (int i=0;i<facts.size();i++) if (facts.get(i).key().equals(key)) return i;
        return -1;
    }
    private static String fieldName(String field) {
        return switch(field) { case "title" -> "标题"; case "content" -> "正文"; case "summary" -> "摘要"; default -> "内容"; };
    }
    private static Problem error(String detail) {
        return new Problem(502,"模型修订清单无法安全应用："+detail+"；原有内容未覆盖，未自动重试");
    }
}
