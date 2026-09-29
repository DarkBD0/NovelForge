package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Action;
import com.novelforge.novel.Novel.Artifact;
import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks explicit act budgets locally; numeric hard rules must not depend on model judgment. */
@Component
public class OutlineBudgetAudit {
    private static final Pattern ACT_BUDGET=Pattern.compile(
            "(?m)^\\s*第[一二三四五六七八九十百零〇0-9]+幕[^\\r\\n]{0,160}?约\\s*([0-9０-９][0-9０-９,，]*)\\s*字");
    private static final Pattern DECLARED_TOTAL=Pattern.compile(
            "(?:合计|总计|总字数|全书|规划)[^。；;\\r\\n]{0,40}?"+
                    "((?:[0-9０-９]+(?:[.．][0-9０-９]+)?|[零〇一二三四五六七八九两十]+(?:点[零〇一二三四五六七八九]+)?)万|[0-9０-９][0-9０-９,，]*)\\s*字");

    public Review apply(Novel novel,Artifact target,Action action,ModelGateway.Generated candidate,Review review) {
        boolean outline=action==Action.OUTLINE || (target!=null && target.kind==Kind.OUTLINE);
        if (!outline || candidate==null || candidate.content()==null || review==null) return review;
        List<Long> allocations=allocations(candidate.content());
        if (allocations.size()<2) return review;
        review=withoutModelBudgetJudgment(review);
        long total=0;
        try { for (long value:allocations) total=Math.addExact(total,value); }
        catch (ArithmeticException e) { return review.withIssue(issue("阶段字数合计超出系统可计算范围","请缩小并重新分配各阶段字数")); }
        if (total>novel.approvedMaxWords)
            return review.withIssue(issue("各阶段预计字数合计为"+total+"字，超过当前批准上限"+novel.approvedMaxWords+"字",
                    "压缩阶段预算，或由作者明确批准提高字数上限"));
        Long declared=declaredTotal(candidate.content()+"\n"+(candidate.summary()==null?"":candidate.summary()));
        if (declared!=null && declared!=total)
            return review.withIssue(issue("大纲声明的总字数为"+declared+"字，但各阶段预计字数合计为"+total+"字",
                    "统一总字数说明与各阶段分配，避免前后矛盾"));
        return review;
    }

    private Review withoutModelBudgetJudgment(Review review) {
        var retained=review.issueDetails().stream().filter(issue->!budgetIssue(issue)).toList();
        if (retained.size()==review.issueDetails().size()) return review;
        boolean passed=retained.stream().noneMatch(issue->"必须修正".equals(issue.severity()));
        return new Review(passed,retained.stream().map(ReviewIssue::text).toList(),review.mainlineResolved(),
                review.endingClear(),review.foreshadowingResolved(),retained);
    }

    private boolean budgetIssue(ReviewIssue issue) {
        String text=(issue.location()+" "+issue.problem()+" "+issue.evidence()).toLowerCase(Locale.ROOT);
        return text.contains("字数") && List.of("阶段","各幕","预算","合计","总计","目标","上限","分配")
                .stream().anyMatch(text::contains);
    }

    Long declaredTotal(String text) {
        Matcher matcher=DECLARED_TOTAL.matcher(text);
        while (matcher.find()) {
            String statement=matcher.group();
            if (statement.contains("上限")||statement.contains("最多")||statement.contains("不超过")) continue;
            Long amount=parseAmount(matcher.group(1));
            if (amount!=null) return amount;
        }
        return null;
    }

    private Long parseAmount(String raw) {
        String normalized=raw.replace("，","").replace(",","").replace("．",".");
        normalized=toAsciiDigits(normalized);
        try {
            if (!normalized.endsWith("万")) return Long.parseLong(normalized);
            String value=normalized.substring(0,normalized.length()-1);
            if (value.matches("[0-9]+(?:\\.[0-9]+)?")) return Math.round(Double.parseDouble(value)*10_000);
            int point=value.indexOf('点');
            String whole=point<0?value:value.substring(0,point);
            long integer=chineseInteger(whole);
            if (integer<0) return null;
            double number=integer;
            if (point>=0) {
                String decimals=value.substring(point+1); double place=.1;
                for (int i=0;i<decimals.length();i++) { int digit=chineseDigit(decimals.charAt(i)); if(digit<0)return null; number+=digit*place; place/=10; }
            }
            return Math.round(number*10_000);
        } catch (NumberFormatException ignored) { return null; }
    }

    private String toAsciiDigits(String value) {
        var result=new StringBuilder();
        value.codePoints().forEach(cp->result.appendCodePoint(cp>='０'&&cp<='９'?cp-'０'+'0':cp));
        return result.toString();
    }

    private int chineseInteger(String value) {
        if (value.length()==1) return chineseDigit(value.charAt(0));
        int ten=value.indexOf('十');
        if (ten>=0) {
            int tens=ten==0?1:chineseDigit(value.charAt(0));
            int ones=ten==value.length()-1?0:chineseDigit(value.charAt(ten+1));
            return tens<0||ones<0?-1:tens*10+ones;
        }
        int number=0;
        for(int i=0;i<value.length();i++){int digit=chineseDigit(value.charAt(i));if(digit<0)return -1;number=number*10+digit;}
        return number;
    }

    private int chineseDigit(char value) {
        int index="零〇一二两三四五六七八九".indexOf(value);
        return switch (index) {
            case 0,1 -> 0; case 2 -> 1; case 3,4 -> 2; case 5 -> 3; case 6 -> 4;
            case 7 -> 5; case 8 -> 6; case 9 -> 7; case 10 -> 8; case 11 -> 9; default -> -1;
        };
    }

    List<Long> allocations(String content) {
        var values=new ArrayList<Long>();
        var matcher=ACT_BUDGET.matcher(content);
        while (matcher.find()) {
            String normalized=matcher.group(1).replace(",","").replace("，","");
            try { values.add(Long.parseLong(toAsciiDigits(normalized))); }
            catch (NumberFormatException ignored) { return List.of(); }
        }
        return List.copyOf(values);
    }

    private ReviewIssue issue(String problem,String suggestion) {
        return new ReviewIssue("大纲 > 阶段目标与字数分配",problem,
                "本地按各幕明确标注的预计字数进行确定性求和",suggestion,"必须修正");
    }
}
