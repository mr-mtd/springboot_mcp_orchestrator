package io.github.mrmtd.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * REST orchestrator for the React front end. It is an MCP client of the transport MCP server
 * (Streamable HTTP), calls its tools, and combines their results into front-end shaped responses.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
