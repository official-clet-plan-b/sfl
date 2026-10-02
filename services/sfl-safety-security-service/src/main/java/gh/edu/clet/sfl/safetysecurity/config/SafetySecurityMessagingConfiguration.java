package gh.edu.clet.sfl.safetysecurity.config;

import gh.edu.clet.sfl.common.web.RabbitHealthConfigurationValidator;
import gh.edu.clet.sfl.safetysecurity.platform.application.PlatformThreads;
import gh.edu.clet.sfl.safetysecurity.platform.infrastructure.messaging.SafetySecurityEventTransport;
import gh.edu.clet.sfl.safetysecurity.platform.infrastructure.messaging.SafetySecurityEventTransports;
import java.time.Duration;
import java.util.Locale;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * Chooses the {@code safety_security} outbox transport, and fails loudly when it cannot - ADR 0010.
 *
 * <p>Mirrors {@code EmergencyMessagingConfiguration} and facilities' messaging configuration: selecting
 * {@code rabbitmq} without a {@code RabbitTemplate}, or naming a transport that does not exist, raises at
 * startup rather than letting the service run and silently drop every event.
 *
 * <p>{@code sfl.safety-security.messaging.transport} defaults to whatever S174's transport is set to
 * (application.yml), so the one environment variable that already turns on SSEMP's broker
 * ({@code SFL_EMERGENCY_EVENT_TRANSPORT}) turns on both drainers - a deployment that configured S174 for
 * RabbitMQ and then found the rest of the service still on {@code local} would be the
 * configured-but-not-connected failure this whole package exists to end.
 */
@Configuration(proxyBeanMethods = false)
class SafetySecurityMessagingConfiguration {

    static final String TRANSPORT_PROPERTY = "sfl.safety-security.messaging.transport";

    @Bean
    SafetySecurityEventTransport safetySecurityEventTransport(
            @Value("${" + TRANSPORT_PROPERTY + ":local}") String transport,
            @Value("${sfl.safety-security.messaging.exchange:sfl.events}") String exchange,
            @Value("${sfl.safety-security.messaging.confirm-timeout:PT5S}") Duration confirmTimeout,
            ObjectProvider<RabbitTemplate> rabbitTemplate) {
        String selected = transport == null ? "" : transport.strip().toLowerCase(Locale.ROOT);
        return switch (selected) {
            case "rabbitmq" -> {
                RabbitTemplate template = rabbitTemplate.getIfAvailable();
                if (template == null) {
                    throw new IllegalStateException(TRANSPORT_PROPERTY + "=rabbitmq but no RabbitTemplate is "
                            + "available. Configure spring.rabbitmq.* or select the local transport deliberately.");
                }
                yield SafetySecurityEventTransports.rabbitMq(template, exchange, confirmTimeout);
            }
            case "local" -> SafetySecurityEventTransports.local();
            default -> throw new IllegalStateException("Unknown " + TRANSPORT_PROPERTY + " '" + transport
                    + "'. Supported transports are: local, rabbitmq.");
        };
    }

    /** The same broker-health guard S174's transport already has - see {@link RabbitHealthConfigurationValidator}. */
    @Bean
    InitializingBean safetySecurityRabbitHealthConfigurationCheck(
            @Value("${" + TRANSPORT_PROPERTY + ":local}") String transport,
            @Value("${management.health.rabbit.enabled:false}") boolean rabbitHealthEnabled) {
        return () -> RabbitHealthConfigurationValidator.validate(TRANSPORT_PROPERTY, transport,
                "management.health.rabbit.enabled", rabbitHealthEnabled);
    }

    /**
     * The broker listener's threads are platform threads - {@code SafetySecurityIntegrationListener} is
     * {@code @Transactional}, so the row-level-security scope has to be on the thread before the
     * transaction begins. See {@link PlatformThreads}.
     *
     * <p>A post-processor rather than a replacement factory, so every setting Boot applied from
     * {@code spring.rabbitmq.listener.*} is kept and only the executor changes. Same as facilities.
     */
    @Bean
    static BeanPostProcessor safetySecurityPlatformListenerThreads() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof SimpleRabbitListenerContainerFactory factory) {
                    factory.setTaskExecutor(new SimpleAsyncTaskExecutor(
                            PlatformThreads.factory("sfl-safety-security-listener-")));
                }
                return bean;
            }
        };
    }
}
