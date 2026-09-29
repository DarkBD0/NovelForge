package com.novelforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelforge.infrastructure.NovelRepository;
import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Review;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:repository;DB_CLOSE_DELAY=-1")
class RepositoryTest {
    @Autowired NovelRepository repository;
    @Test void roundTripsAndRollsBackFailedMutation() {
        Novel n = new Novel(); n.title = "未完成的灯塔"; n.synopsis = "寻找灯塔";
        repository.insert(n);
        assertThat(repository.get(n.id).title).isEqualTo(n.title);
        assertThatThrownBy(() -> repository.update(n.id, x -> { x.title = "错误"; throw new IllegalStateException(); }));
        assertThat(repository.get(n.id).title).isEqualTo(n.title);
    }
    @Test void oldStringOnlyReviewDataRemainsReadable() throws Exception {
        String old="{\"passed\":false,\"issues\":[\"王阿姨顺序冲突\"],\"mainlineResolved\":false,\"endingClear\":false,\"foreshadowingResolved\":false}";
        Review review=new ObjectMapper().readValue(old,Review.class);
        assertThat(review.issues()).containsExactly("王阿姨顺序冲突");
        assertThat(review.issueDetails()).singleElement().satisfies(issue->{
            assertThat(issue.problem()).isEqualTo("王阿姨顺序冲突");
            assertThat(issue.location()).isEqualTo("当前内容");
        });
    }
}
