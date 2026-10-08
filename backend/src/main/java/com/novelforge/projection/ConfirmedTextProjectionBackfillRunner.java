package com.novelforge.projection;

import com.novelforge.infrastructure.NovelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Order(20)
@ConditionalOnProperty(name="novelforge.storage.mode",havingValue="normalized")
public class ConfirmedTextProjectionBackfillRunner implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(ConfirmedTextProjectionBackfillRunner.class);
    private final NovelRepository repository;
    private final ConfirmedTextProjectionStore store;
    private final TransactionTemplate transactions;

    public ConfirmedTextProjectionBackfillRunner(NovelRepository repository,ConfirmedTextProjectionStore store,
                                                 TransactionTemplate transactions) {
        this.repository=repository; this.store=store; this.transactions=transactions;
    }

    @Override public void run(ApplicationArguments args) {
        int changed=0;
        for(var novel:repository.list()) {
            var result=transactions.execute(status->store.synchronize(novel));
            if(result!=null && result.changed()) changed++;
        }
        if(changed>0) log.info("Confirmed text projection source synchronized for {} novel(s)",changed);
    }
}
