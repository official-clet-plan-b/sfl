package gh.edu.clet.sfl.safetysecurity.emergency.config;

import gh.edu.clet.sfl.safetysecurity.platform.application.PlatformThreads;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
class EmergencyServiceConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Without this, {@code @EnableScheduling} falls back to a single-thread pool shared by every
     * {@code @Scheduled} job in the service - the emergency sweep and the outbox drainer - so a slow
     * one serializes behind the other. Mirrors {@code FleetServiceConfiguration}'s reasoning and sizing
     * convention: headroom above the current (small) job count.
     *
     * <p>The service's only scheduler, so it serves every module's jobs, not just S174's. Raised from
     * four for Phase 2 - the {@code safety_security} outbox drainer and S165's review sweep join the
     * emergency sweep, the S174 drainer and the life-safety sweep. Its threads are platform threads, so
     * row-level security scopes every sweep to '*' rather than to nothing; see {@link PlatformThreads}.
     */
    @Bean
    @ConditionalOnMissingBean(TaskScheduler.class)
    TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(8);
        scheduler.setThreadNamePrefix("sfl-safety-security-scheduler-");
        scheduler.setThreadFactory(PlatformThreads.factory("sfl-safety-security-scheduler-"));
        return scheduler;
    }
}
