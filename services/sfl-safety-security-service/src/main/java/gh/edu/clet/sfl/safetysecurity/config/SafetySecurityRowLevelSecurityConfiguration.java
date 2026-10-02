package gh.edu.clet.sfl.safetysecurity.config;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SiteScopeGuc;
import gh.edu.clet.sfl.safetysecurity.platform.application.PlatformThreads;
import gh.edu.clet.sfl.safetysecurity.riskassessment.api.RiskAssessmentActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Registers the site-scope GUC on the transaction manager - ADR 0007, ADR 0010.
 *
 * <p>The SSEMP twin of {@code FacilitiesRowLevelSecurityConfiguration}, arriving with SSEMP's first
 * policy-carrying tables (S165, V19). Enabled by default and harmless where it is not needed: setting
 * {@code app.site_scopes} on a connection whose role bypasses RLS - the schema owner, which every
 * environment connects as today - costs one statement per transaction and changes nothing. So the setting
 * is already right everywhere before an environment adopts {@code sfl_app}, and switching one over is a
 * connection-string change rather than a deployment that must land in lockstep with a migration.
 *
 * <p><strong>Which resolver.</strong> Each SSEMP module keeps its own copy of the actor resolver, and
 * they derive the principal identically - JWT claims in production, {@code X-SFL-*} headers in
 * development - so any of them answers "which sites is this caller scoped to" the same way. S165's is used
 * because S165 owns the only tables the policy currently applies to.
 *
 * <p>{@code sfl.security.rls.set-scope-enabled=false} turns it off.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sfl.security.rls.set-scope-enabled", havingValue = "true", matchIfMissing = true)
class SafetySecurityRowLevelSecurityConfiguration {

    /** A plain bean: Boot hands every {@code TransactionExecutionListener} to the transaction manager itself. */
    @Bean
    SiteScopeGuc safetySecuritySiteScopeGuc(DataSource dataSource,
            ObjectProvider<RiskAssessmentActorResolver> actorResolver) {
        return new SiteScopeGuc(dataSource, () -> currentScopes(actorResolver));
    }

    /**
     * The scopes of whoever is on this request, or none. A scheduler, drainer or broker-listener thread is
     * the service account's and scopes to every site; anything else without a request scopes to nothing and
     * reads no rows from a policy-carrying table.
     */
    static Set<String> currentScopes(ObjectProvider<RiskAssessmentActorResolver> actorResolver) {
        if (PlatformThreads.isPlatformThread()) {
            return Set.of(SiteScopeGuc.ALL_SITES);
        }
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Set.of();
        }
        RiskAssessmentActorResolver resolver = actorResolver.getIfAvailable();
        if (resolver == null) {
            return Set.of();
        }
        try {
            HttpServletRequest request = attributes.getRequest();
            ActorContext actor = resolver.resolve(request);
            return actor.principal().siteScopes();
        } catch (RuntimeException resolutionFailed) {
            // An unresolvable actor scopes to nothing rather than to everything.
            return Set.of();
        }
    }
}
