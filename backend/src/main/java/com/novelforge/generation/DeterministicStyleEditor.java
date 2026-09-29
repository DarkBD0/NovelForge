package com.novelforge.generation;

import com.novelforge.novel.Novel.Kind;
import com.novelforge.novel.Novel.ReviewIssue;
import com.novelforge.novel.Novel.Version;
import com.novelforge.shared.Problem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts only unambiguous style findings into exact, locally verifiable deletions. */
@Component
public class DeterministicStyleEditor {
    private static final Pattern QUOTE=Pattern.compile("[“\"]([^”\"]{4,300})[”\"]");
    private static final Pattern REDUNDANCY=Pattern.compile("重复|复述|赘句|重复解释|又.{0,12}解释|相同.{0,8}(?:意思|结论|信息)");
    private static final Pattern DELETE=Pattern.compile("删除|删去|删掉");
    private static final Pattern AMBIGUOUS=Pattern.compile("改成|改为|替换|保留|精简|压缩|由作者|可以选择|或");

    public boolean canApply(Version version) {
        return !safeOperations(version).isEmpty();
    }

    public ModelGateway.Generated apply(Version version) {
        List<RewritePatch.Operation> operations=safeOperations(version);
        if (operations.isEmpty())
            throw new Problem(409,"当前文风建议没有可安全自动删除的重复解释；建议仍会保留，请由作者决定是否修改");
        return StylePatchGuard.apply(version,new RewritePatch(operations),Kind.CHAPTER);
    }

    private List<RewritePatch.Operation> safeOperations(Version version) {
        List<RewritePatch.Operation> safe=new ArrayList<>();
        for (RewritePatch.Operation operation:operations(version)) {
            try {
                StylePatchGuard.apply(version,new RewritePatch(List.of(operation)),Kind.CHAPTER);
                safe.add(operation);
            } catch (Problem ignored) {
                // Unsafe findings remain visible as advice; they never create a candidate version.
            }
        }
        return List.copyOf(safe);
    }

    List<RewritePatch.Operation> operations(Version version) {
        if (version==null || version.content==null || version.styleReview==null
                || version.styleReview.issueDetails()==null) return List.of();
        List<RewritePatch.Operation> operations=new ArrayList<>();
        for (ReviewIssue issue:version.styleReview.issueDetails()) {
            if (operations.size()>=8 || !eligible(issue)) continue;
            String quote=exactQuote(issue.evidence(),version.content);
            if (quote==null || operations.stream().anyMatch(op->op.oldText().equals(quote))) continue;
            operations.add(new RewritePatch.Operation("REPLACE_TEXT","content",null,quote,"",
                    null,null,null,null));
        }
        return List.copyOf(operations);
    }

    private boolean eligible(ReviewIssue issue) {
        if (issue==null || !"建议优化".equals(issue.severity())) return false;
        String problem=issue.problem()==null?"":issue.problem();
        String suggestion=issue.suggestion()==null?"":issue.suggestion();
        return REDUNDANCY.matcher(problem).find() && DELETE.matcher(suggestion).find()
                && !AMBIGUOUS.matcher(suggestion).find();
    }

    private String exactQuote(String evidence,String content) {
        Matcher matcher=QUOTE.matcher(evidence==null?"":evidence);
        while (matcher.find()) {
            String quote=matcher.group(1).strip();
            if (quote.length()>300 || count(content,quote)!=1 || quote.endsWith("……") || quote.endsWith("...")) continue;
            return quote;
        }
        return null;
    }

    private int count(String text,String token) {
        int count=0,from=0;
        while ((from=text.indexOf(token,from))>=0) { count++; from+=token.length(); }
        return count;
    }
}
