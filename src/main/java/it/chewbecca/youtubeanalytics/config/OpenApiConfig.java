package it.chewbecca.youtubeanalytics.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI applicationOpenApi() {
        String schemeName = "bearerApiKey";
        return new OpenAPI()
                .info(new Info()
                        .title("I Discorsi di Chewbecca - YouTube Analytics API")
                        .version("1.0.0")
                        .description("API private per interrogare YouTube Data API e YouTube Analytics tramite GPT Actions."))
                .components(new Components().addSecuritySchemes(
                        schemeName,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("API key")
                ))
                .addSecurityItem(new SecurityRequirement().addList(schemeName));
    }
}
