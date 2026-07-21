package org.ledger.infrastructure;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the {@code info} block and the {@code ApiKeyAuth} security scheme from
 * planning/05-api-design.md §7. Generated from the real controllers/DTOs (ADR 0004), so the served
 * document cannot drift from the implemented surface.
 */
@Configuration
public class OpenApiConfig {

  private static final String SECURITY_SCHEME_NAME = "ApiKeyAuth";

  @Bean
  public OpenAPI ledgerOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Transactional Payments Ledger API")
                .version("1.0.0")
                .description(
                    "A balanced-entry bookkeeping ledger API. Every transfer produces a matched "
                        + "pair of immutable ledger entries that sum to zero. All amounts are in "
                        + "minor currency units."))
        .components(
            new Components()
                .addSecuritySchemes(
                    SECURITY_SCHEME_NAME,
                    new SecurityScheme()
                        .name("Authorization")
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .description("Pass your API key as `ApiKey <key>`.")))
        .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
  }
}
