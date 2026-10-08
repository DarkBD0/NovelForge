package com.novelforge.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchProjectionClientQueryTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void givesChineseBigramsPriorityWhileKeepingLegacyFallbackAndExactTitleBoost() throws Exception {
        ElasticsearchProjectionClient client=new ElasticsearchProjectionClient(mapper,null,
                "http://127.0.0.1:9200","novelforge-test");

        String request=mapper.writeValueAsString(client.searchBody("novel-1",
                "1200Hz为什么不是普通耳鸣",31,5));

        assertThat(request).contains("title.cjk^8","summary.cjk^4","text.cjk")
                .contains("title.exact","\"boost\":20")
                .contains("\"novelId\":\"novel-1\"")
                .contains("\"lt\":31")
                .contains("\"authorityState\":\"CONFIRMED\"");
    }
}
