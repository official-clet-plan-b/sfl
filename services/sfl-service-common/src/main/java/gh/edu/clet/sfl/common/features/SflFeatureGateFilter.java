package gh.edu.clet.sfl.common.features;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses calls into a switched-off system with {@code 404 SFL_SYSTEM_DISABLED}.
 *
 * <p>404 rather than 403: the actor's roles are not the problem - the module is simply not part of
 * this deployment. The body uses the standard {@code ApiResponse} envelope so the UI's existing
 * error handling reads it.
 *
 * <p>Registered after Spring Security, so an unauthenticated caller is still told to authenticate
 * first and learns nothing about which modules exist.
 */
public class SflFeatureGateFilter extends OncePerRequestFilter {

    public static final String ERROR_CODE = "SFL_SYSTEM_DISABLED";

    private final SflFeatureGate gate;

    public SflFeatureGateFilter(SflFeatureGate gate) {
        this.gate = gate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Optional<String> system = gate.systemFor(path);
        if (system.isEmpty() || gate.isApiOpen(system.get())) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        String correlationId = Optional.ofNullable(request.getHeader("X-Correlation-Id")).orElse("");
        response.getWriter().write("{\"data\":null,\"error\":{\"code\":\"" + ERROR_CODE + "\","
                + "\"message\":\"" + json(system.get()) + " is switched off in this deployment.\","
                + "\"correlationId\":\"" + json(correlationId) + "\","
                + "\"timestamp\":\"" + Instant.now() + "\"}}");
    }

    private static String json(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c >= 0x20) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
