package com.novelforge.projection;

import com.novelforge.infrastructure.NovelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Order(30)
@ConditionalOnProperty(name="novelforge.projections.neo4j.enabled",havingValue="true")
public class Neo4jProjectionSourceBackfillRunner implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(Neo4jProjectionSourceBackfillRunner.class);
    private final NovelRepository repository;
    private final Neo4jProjectionSourceStore store;
    private final TransactionTemplate transactions;

    public Neo4jProjectionSourceBackfillRunner(NovelRepository repository,Neo4jProjectionSourceStore store,
                                               TransactionTemplate transactions) {
        this.repository=repository; this.store=store; this.transactions=transactions;
    }

    @Override public void run(ApplicationArguments args) {
        int changed=0;
        for(var novel:repository.list()) {
            var result=transactions.execute(status->store.synchronize(novel.id));
            if(result!=null&&result.changed()) changed++;
        }
        if(changed>0) log.info("Neo4j projection source synchronized for {} novel(s)",changed);
    }
}
