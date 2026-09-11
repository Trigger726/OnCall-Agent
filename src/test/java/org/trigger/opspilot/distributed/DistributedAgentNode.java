package org.trigger.opspilot.distributed;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.trigger.opspilot.OpsPilotApplication;
import org.trigger.opspilot.incident.IncidentService;
import org.trigger.opspilot.investigation.tool.InvestigationTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Test-only JVM entry point; not included in the production JAR. */
public class DistributedAgentNode {
    public static void main(String[] args) {
        new SpringApplicationBuilder(OpsPilotApplication.class, GateConfig.class).run(args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "opspilot.test.gated-tool", havingValue = "true")
    static class GateConfig {
        @Bean
        InvestigationTool crossInstanceGate(@Value("${opspilot.test.gate-directory}") String directory) {
            return new InvestigationTool() {
                public int order() { return 26; }
                public String name() { return "cross_instance_gate"; }
                public String title() { return "等待两个实例订阅就绪"; }
                public ToolResult execute(IncidentService.IncidentDetail incident) {
                    try {
                        Path gate = Path.of(directory);
                        Files.writeString(gate.resolve("entered"), "ready");
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                        while (!Files.exists(gate.resolve("release"))) {
                            if (System.nanoTime() > deadline) throw new IllegalStateException("Gate timeout");
                            Thread.sleep(25);
                        }
                        return new ToolResult("两个实例的订阅已建立", List.of());
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Gate interrupted", exception);
                    } catch (java.io.IOException exception) {
                        throw new IllegalStateException("Gate unavailable", exception);
                    }
                }
            };
        }
    }
}
