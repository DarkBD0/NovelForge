package com.novelforge.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name="novelforge.projections.elasticsearch.enabled",havingValue="true")
public class ElasticsearchProjectionWorker implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(ElasticsearchProjectionWorker.class);
    private final ProjectionOutbox outbox;
    private final ElasticsearchProjectionClient elasticsearch;
    private final AtomicBoolean active=new AtomicBoolean();

    public ElasticsearchProjectionWorker(ProjectionOutbox outbox,ElasticsearchProjectionClient elasticsearch) {
        this.outbox=outbox; this.elasticsearch=elasticsearch;
    }

    @Override public void run(ApplicationArguments args) {
        outbox.enqueueMissingElasticsearchSnapshots();
        drain(20);
    }

    @Scheduled(fixedDelayString="${novelforge.projections.poll-interval-ms:5000}")
    public void poll() { drain(1); }

    private void drain(int limit) {
        if(!active.compareAndSet(false,true)) return;
        try {
            for(int i=0;i<limit;i++) {
                ProjectionOutbox.Event event=outbox.claimNextElasticsearch();
                if(event==null) return;
                try {
                    if(!ProjectionOutbox.EVENT_CONFIRMED_TEXT_SNAPSHOT.equals(event.eventType())) {
                        throw new IllegalStateException("未知 Elasticsearch 投影事件："+event.eventType());
                    }
                    elasticsearch.replaceNovel(event.novelId());
                    outbox.succeeded(event);
                    log.info("Elasticsearch shadow projection updated: novel={} event={}",event.novelId(),event.id());
                } catch(Exception failure) {
                    outbox.failed(event,failure);
                    log.warn("Elasticsearch shadow projection deferred: novel={} event={} reason={}",
                            event.novelId(),event.id(),failure.getClass().getSimpleName());
                }
            }
        } finally { active.set(false); }
    }
}
