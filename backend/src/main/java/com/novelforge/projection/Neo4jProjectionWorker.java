package com.novelforge.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Order(40)
@ConditionalOnProperty(name="novelforge.projections.neo4j.enabled",havingValue="true")
public class Neo4jProjectionWorker implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(Neo4jProjectionWorker.class);
    private final ProjectionOutbox outbox;
    private final Neo4jProjectionClient neo4j;
    private final AtomicBoolean active=new AtomicBoolean();

    public Neo4jProjectionWorker(ProjectionOutbox outbox,Neo4jProjectionClient neo4j) { this.outbox=outbox; this.neo4j=neo4j; }

    @Override public void run(ApplicationArguments args) {
        outbox.enqueueMissingNeo4jSnapshots();
        drain(20);
    }

    @Scheduled(fixedDelayString="${novelforge.projections.poll-interval-ms:5000}")
    public void poll() { drain(1); }

    private void drain(int limit) {
        if(!active.compareAndSet(false,true)) return;
        try {
            for(int i=0;i<limit;i++) {
                ProjectionOutbox.Event event=outbox.claimNextNeo4j();
                if(event==null) return;
                try {
                    if(!ProjectionOutbox.EVENT_MEMORY_SNAPSHOT.equals(event.eventType())) throw new IllegalStateException("未知 Neo4j 投影事件："+event.eventType());
                    neo4j.replaceNovel(event.novelId());
                    outbox.succeeded(event);
                    log.info("Neo4j projection updated: novel={} event={}",event.novelId(),event.id());
                } catch(Exception failure) {
                    outbox.failed(event,failure);
                    log.warn("Neo4j projection deferred: novel={} event={} reason={}",event.novelId(),event.id(),failure.getClass().getSimpleName());
                }
            }
        } finally { active.set(false); }
    }
}
