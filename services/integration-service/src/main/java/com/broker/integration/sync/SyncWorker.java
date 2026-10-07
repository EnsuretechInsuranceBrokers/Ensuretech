package com.broker.integration.sync;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls for due jobs and runs up to `concurrency` at once, so one slow TPA cannot hold up the others. */
@Component
public class SyncWorker {
    private final SyncService sync;
    private final boolean enabled;
    private final int concurrency;
    private final ExecutorService pool;
    private final AtomicInteger active = new AtomicInteger();

    SyncWorker(SyncService sync, @Value("${app.sync.worker-enabled:true}") boolean enabled, @Value("${app.sync.concurrency:4}") int concurrency) {
        this.sync = sync;
        this.enabled = enabled;
        this.concurrency = concurrency;
        this.pool = Executors.newFixedThreadPool(concurrency, Thread.ofPlatform().name("tpa-sync-", 0).daemon(true).factory());
    }

    @Scheduled(fixedDelayString = "${app.sync.poll-ms:2000}")
    void tick() {
        if (!enabled) return;
        while (active.get() < concurrency) {
            var id = sync.claimNext();
            if (id.isEmpty()) return;
            active.incrementAndGet();
            pool.submit(() -> {
                try { sync.process(id.get()); } finally { active.decrementAndGet(); }
            });
        }
    }

    /** Test / operator hook: process due jobs on the calling thread until none are left. Returns how many ran. */
    public int drain() {
        int n = 0;
        for (var id = sync.claimNext(); id.isPresent(); id = sync.claimNext()) { sync.process(id.get()); n++; }
        return n;
    }
}
