package com.novelforge.canon;

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
@Order(10)
@ConditionalOnProperty(name="novelforge.storage.mode",havingValue="normalized")
public class FormalMemoryBackfillRunner implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(FormalMemoryBackfillRunner.class);
    private final NovelRepository repository;
    private final FormalMemoryStore memory;
    private final TransactionTemplate transactions;

    public FormalMemoryBackfillRunner(NovelRepository repository,FormalMemoryStore memory,TransactionTemplate transactions) {
        this.repository=repository; this.memory=memory; this.transactions=transactions;
    }

    @Override public void run(ApplicationArguments args) {
        int changed=0;
        for(var novel:repository.list()) {
            var result=transactions.execute(status->memory.synchronize(novel));
            if(result!=null && result.changed()) changed++;
        }
        if(changed>0) log.info("Formal memory synchronized for {} novel(s)",changed);
    }
}
