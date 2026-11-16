package gh.edu.clet.sfl.facilities.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description for the facilities service.
 *
 * <p>The development actor headers are declared globally rather than on each operation. Every S152
 * endpoint authorises against them, so a reader of the specification - or someone driving Swagger UI -
 * needs them on the first call they make, and repeating them on sixty operations is how one gets
 * missed.
 */
@Configuration(proxyBeanMethods = false)
class FacilitiesOpenApiConfiguration {

    @Bean
    OpenAPI facilitiesOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SFL Facilities Service - S152 CAFM/IWMS")
                        .version("v1")
                        .description("""
                                Computer-Aided Facility Management / IWMS (SRS-SFL-S152-01..05) for the CLET \
                                Safety, Facilities and Logistics platform.

                                S152 is the IFIMP host platform: it owns the estate hierarchy (site, building, \
                                floor, space), zones, device references, the facility asset register and the \
                                readiness model that S153 maintenance and S159 room booking build on.

                                Authorisation is by role and site scope on every operation. In local development \
                                (`sfl.security.enabled=false`) the actor is supplied by the `X-SFL-*` headers; in \
                                production the same context is derived from the OIDC/JWT principal.
                                """)
                        .license(new License().name("GSL internal")))
                .components(new Components()
                        .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Enterprise OIDC access token. Used when sfl.security.enabled=true.")));
    }

    @Bean
    OpenApiCustomizer facilitiesActorHeaders() {
        return openApi -> openApi.getPaths().values().forEach(path ->
                path.readOperations().forEach(operation -> {
                    operation.addParametersItem(header("X-SFL-User",
                            "Development actor id. Ignored when a JWT is present.", "facilities.officer"));
                    operation.addParametersItem(header("X-SFL-Roles",
                            "Comma-separated SflRole names, e.g. FACILITIES_MANAGER.", "FACILITIES_MANAGER"));
                    operation.addParametersItem(header("X-SFL-Sites",
                            "Comma-separated site codes the actor is scoped to, or * for all.", "ACCRA"));
                    operation.addParametersItem(header("X-Correlation-ID",
                            "Trace identifier echoed on the response and written to audit. Minted when absent.",
                            null));
                }));
    }

    private static Parameter header(String name, String description, String example) {
        HeaderParameter parameter = new HeaderParameter();
        parameter.setName(name);
        parameter.setDescription(description);
        parameter.setRequired(false);
        parameter.setSchema(new io.swagger.v3.oas.models.media.StringSchema());
        if (example != null) {
            parameter.setExample(example);
        }
        return parameter;
    }
}
