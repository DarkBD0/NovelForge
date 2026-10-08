package com.novelforge.generation;

import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** High-precision gate applied only to the experimental continuity checker. */
@Component
public class ContinuityReviewPolicy {
    public static final String VERSION="2026-10-05-continuity-v3.3";
    private static final Pattern ALLOWED_ID=Pattern.compile(
            "^CONTINUITY:(IDENTITY|RELATION|LIFE_STATE|OBJECT_LOCATION|OBJECT_OWNERSHIP|ABSOLUTE_TIME|WORLD_RULE|ABILITY_BOUNDARY):HIGH$");
    private static final List<String> PROOF_FIELDS=List.of(
            "冲突对象","冲突属性","原状态时间","候选状态时间","同一时点","推进授权");
    private static final Pattern SAME_TIME=Pattern.compile("同一时点\\s*[：:]\\s*[‘’“\"]?(?:是|同一|成立)");
    private static final Pattern NO_AUTHORIZATION=Pattern.compile("推进授权\\s*[：:]\\s*[‘’“\"]?(?:无|没有|不存在)");
    private static final Pattern NARRATIVE_CHOICE=Pattern.compile(
            "补一句|补写.{0,12}(?:过程|过渡|衔接)|缺少.{0,12}(?:交代|过渡|衔接)|"
                    +"未交代|读者.{0,6}(?:误解|理解)|容易.{0,6}(?:误解|理解)|不够(?:明确|自然|完整)|"
                    +"为何|为什么|中间程序|司法过程|程序推进|时间跳跃|状态跳变");
    private static final Pattern ORDINARY_PROGRESSION=Pattern.compile(
            "(?:尚未|未定|等待|调查中|接受核查|接受询问).{0,40}(?:已经|落定|判刑|追责|结果)|"
                    +"(?:已经|落定|判刑|追责|结果).{0,40}(?:尚未|未定|等待|调查中|接受核查|接受询问)|"
                    +"雨.{0,12}停|停.{0,12}雨");
    private static final Pattern EXCLUSIVE_OBJECT_CLAIM=Pattern.compile(
            "唯一(?:的)?(?:开启|打开|对应|用途|位置|归属|持有)|只能(?:用于|打开|开启|对应|位于|属于|持有)");

    private static final List<InferredId> INFERRED_IDS=List.of(
            new InferredId("CONTINUITY:IDENTITY:HIGH",Pattern.compile("身份|姓名|性别|年龄")),
            new InferredId("CONTINUITY:RELATION:HIGH",Pattern.compile(
                    "关系|亲属|父子|父女|母子|母女|夫妻|恋人|同事|共事|委托|受托")),
            new InferredId("CONTINUITY:LIFE_STATE:HIGH",Pattern.compile("生死|存活|死亡状态|生命状态")),
            new InferredId("CONTINUITY:OBJECT_LOCATION:HIGH",Pattern.compile(
                    "(?:发现|存放|所在)?位置|地点|方位|(?:首次)?(?:获得|取得|发现)来源|首次获得|首次取得")),
            new InferredId("CONTINUITY:OBJECT_OWNERSHIP:HIGH",Pattern.compile("归属|所有权|持有人|拥有者")),
            new InferredId("CONTINUITY:ABSOLUTE_TIME:HIGH",Pattern.compile(
                    "绝对时间|日期|年月日|钟点|具体起止时间|起止时间|值班时段|值班时间")),
            new InferredId("CONTINUITY:WORLD_RULE:HIGH",Pattern.compile("世界规则|规则约束|基础规则")),
            new InferredId("CONTINUITY:ABILITY_BOUNDARY:HIGH",Pattern.compile("能力边界|能力限制|能力规则"))
    );

    public Review normalize(Review review) {
        if (review==null || review.issueDetails()==null) return review;
        List<ReviewIssue> kept=new ArrayList<>();
        for (ReviewIssue issue:review.issueDetails()) {
            if (issue==null) continue;
            String issueId=normalizedIssueId(issue);
            if (issueId==null) continue;
            String evidence=issue.evidence()==null?"":issue.evidence();
            if (PROOF_FIELDS.stream().anyMatch(field->!evidence.contains(field))
                    || !SAME_TIME.matcher(evidence).find() || !NO_AUTHORIZATION.matcher(evidence).find()) continue;
            if (List.of("CONTINUITY:OBJECT_LOCATION:HIGH","CONTINUITY:OBJECT_OWNERSHIP:HIGH").contains(issueId)
                    && EXCLUSIVE_OBJECT_CLAIM.matcher(issue.problem()).find()
                    && !EXCLUSIVE_OBJECT_CLAIM.matcher(evidence).find()) continue;
            String text=issue.problem()+" "+issue.suggestion();
            if (NARRATIVE_CHOICE.matcher(text).find() || ORDINARY_PROGRESSION.matcher(text).find()) continue;
            kept.add(issueId.equals(issue.issueId())?issue:new ReviewIssue(issueId,issue.location(),issue.problem(),
                    issue.evidence(),issue.suggestion(),issue.severity()));
            if (kept.size()==2) break;
        }
        boolean passed=kept.stream().noneMatch(issue->"必须修正".equals(issue.severity()));
        return new Review(passed,kept.stream().map(ReviewIssue::text).toList(),review.mainlineResolved(),
                review.endingClear(),review.foreshadowingResolved(),kept);
    }

    private String normalizedIssueId(ReviewIssue issue) {
        if (issue.issueId()!=null && ALLOWED_ID.matcher(issue.issueId()).matches()) return issue.issueId();
        if (issue.issueId()!=null && !issue.issueId().isBlank()) return null;
        String evidence=issue.evidence()==null?"":issue.evidence();
        String conflictAttribute=fieldValue(evidence,"冲突属性");
        if (conflictAttribute==null) return null;
        List<String> matches=INFERRED_IDS.stream()
                .filter(candidate->candidate.pattern().matcher(conflictAttribute).find())
                .map(InferredId::id).toList();
        return matches.size()==1?matches.getFirst():null;
    }

    private String fieldValue(String evidence,String field) {
        var matcher=Pattern.compile(Pattern.quote(field)+"\\s*[：:]\\s*([^｜|；;\\n]+)").matcher(evidence);
        return matcher.find()?matcher.group(1).trim():null;
    }

    private record InferredId(String id,Pattern pattern) {}
}
