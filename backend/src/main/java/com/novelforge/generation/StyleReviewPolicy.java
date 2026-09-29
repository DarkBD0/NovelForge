package com.novelforge.generation;

import com.novelforge.novel.Novel.Review;
import com.novelforge.novel.Novel.ReviewIssue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps prose advice non-blocking and adds safe, explainable local checks. */
@Component
public class StyleReviewPolicy {
    public static final String VERSION="2026-09-27-v4";

    private static final Pattern QUOTED_EVIDENCE=Pattern.compile(
            "原文\\s*[：:]\\s*[“\"]([\\s\\S]+?)[”\"]");

    private static final Pattern CHINESE_TIMESTAMP=Pattern.compile(
            "[〇零一二三四五六七八九十]{4}年[〇零一二三四五六七八九十]{1,3}月[〇零一二三四五六七八九十]{1,3}日.{0,8}?[〇零一二三四五六七八九十]{1,3}(?:点|时)[〇零一二三四五六七八九十]{1,3}分");
    private static final Pattern NUMERIC_TIMESTAMP=Pattern.compile(
            "(?:19|20)\\d{2}[-/.年]\\d{1,2}[-/.月]\\d{1,2}(?:日)?[ T，,]{0,3}\\d{1,2}[:：时]\\d{2}(?:分)?");
    private static final Pattern MACHINE_EXPLANATION=Pattern.compile(
            "[^。！？!?\\r\\n]{0,80}(?:仅|只)?(?:用于|供|为了|以便)(?:后续)?(?:核对|验证|检查)[^。！？!?\\r\\n]{0,80}[。！？!?]?");
    private static final Pattern NEGATIVE_PROOF=Pattern.compile(
            "[^。！？!?\\r\\n]{0,60}(?:不是另(?:一|外)[^。！？!?\\r\\n]{0,24}|没有再次(?:移交|交付|转交))[^。！？!?\\r\\n]{0,60}[。！？!?]?");

    public Review normalize(Review modelReview,String content) {
        Map<String,ReviewIssue> issues=new LinkedHashMap<>();
        if (modelReview!=null && modelReview.issueDetails()!=null) for (ReviewIssue issue:modelReview.issueDetails()) {
            if (!isGroundedInCurrentContent(issue,content)) continue;
            ReviewIssue advisory=new ReviewIssue(issue.location(),issue.problem(),issue.evidence(),issue.suggestion(),
                    "作者决定".equals(issue.severity())?"作者决定":"建议优化");
            issues.put(key(advisory),advisory);
        }
        addTimestampIssues(issues,content,CHINESE_TIMESTAMP);
        addTimestampIssues(issues,content,NUMERIC_TIMESTAMP);
        addPanoramaIssues(issues,content);
        addMachineExplanationIssues(issues,content);
        List<ReviewIssue> details=new ArrayList<>(issues.values());
        return new Review(true,details.stream().map(ReviewIssue::text).toList(),false,false,false,details);
    }

    private boolean isGroundedInCurrentContent(ReviewIssue issue,String content) {
        if (issue==null || issue.evidence()==null || content==null) return false;
        Matcher matcher=QUOTED_EVIDENCE.matcher(issue.evidence());
        if (!matcher.find()) return false;
        String quote=matcher.group(1).strip();
        return !quote.isBlank() && content.contains(quote);
    }

    private void addTimestampIssues(Map<String,ReviewIssue> issues,String content,Pattern pattern) {
        Matcher matcher=pattern.matcher(content==null?"":content);
        while (matcher.find()) {
            String sentence=sentenceAround(content,matcher.start(),matcher.end());
            ReviewIssue issue=new ReviewIssue("正文中包含完整日期和时分的句子",
                    "时间精确到完整年月日和分钟，读起来像档案记录；如果它不参与倒计时、不在场证明、谜题或因果判断，就没有必要保留这种精度",
                    "原文：“"+sentence+"”",
                    "保留人物真正需要的信息，改成“当晚、三年前、下午”等自然时间，或直接删除时间；若精确时刻是线索则由作者保留",
                    "作者决定");
            issues.putIfAbsent(key(issue),issue);
        }
    }

    private void addPanoramaIssues(Map<String,ReviewIssue> issues,String content) {
        if (content==null) return;
        for (String paragraph:content.split("\\R+")) {
            if (occurrences(paragraph,"看着")<3 && occurrences(paragraph,"望着")<3) continue;
            String evidence=paragraph.strip();
            if (evidence.length()>160) evidence=evidence.substring(0,160)+"……";
            ReviewIssue issue=new ReviewIssue("正文中的连续环境扫描段落",
                    "同一段连续罗列多个被看见的环境或日常活动，可能只是装饰性全景，没有参与人物行动、危险、线索或互动",
                    "原文：“"+evidence+"”",
                    "删除不影响后续行动的景物，只保留人物会利用、避让、调查或受到影响的环境细节",
                    "建议优化");
            issues.putIfAbsent(key(issue),issue);
        }
    }

    private void addMachineExplanationIssues(Map<String,ReviewIssue> issues,String content) {
        addPatternIssues(issues,content,MACHINE_EXPLANATION,"正文中的核对式说明",
                "这句话像写给检查流程看的说明，不像人物当下会说或叙述自然需要的信息",
                "删除这句核对式说明","建议优化");
        addPatternIssues(issues,content,NEGATIVE_PROOF,"正文中的否定式证明",
                "这句话可能是在向检查器证明某件事没有再次发生，而不是推进当前场景",
                "如果删除后不影响读者理解，由作者删除；如果身份区分本身是剧情线索则保留","作者决定");
    }

    private void addPatternIssues(Map<String,ReviewIssue> issues,String content,Pattern pattern,
                                  String location,String problem,String suggestion,String severity) {
        Matcher matcher=pattern.matcher(content==null?"":content);
        while (matcher.find()) {
            String sentence=matcher.group().strip();
            if (sentence.isBlank()) continue;
            ReviewIssue issue=new ReviewIssue(location,problem,"原文：“"+sentence+"”",suggestion,severity);
            issues.putIfAbsent(key(issue),issue);
        }
    }

    private int occurrences(String text,String token) {
        int count=0,from=0;
        while ((from=text.indexOf(token,from))>=0) { count++; from+=token.length(); }
        return count;
    }

    private String sentenceAround(String content,int start,int end) {
        int left=start;
        while (left>0 && "。！？!?\n\r".indexOf(content.charAt(left-1))<0) left--;
        int right=end;
        while (right<content.length() && "。！？!?\n\r".indexOf(content.charAt(right))<0) right++;
        if (right<content.length() && "。！？!?".indexOf(content.charAt(right))>=0) right++;
        String sentence=content.substring(left,right).strip();
        return sentence.length()<=180?sentence:sentence.substring(0,180)+"……";
    }

    private String key(ReviewIssue issue) { return issue.location()+"|"+issue.evidence(); }
}
