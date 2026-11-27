package io.github.mrmtd.orchestrator.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Title and description of the OpenAPI document behind Swagger UI ({@code /swagger}). */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("Transport MCP orchestrator")
                .version("v1")
                .description("Chat API for the React front end. An OpenAI model answers each message, "
                        + "calling the transport MCP server's tools when it needs data."));
    }
}
