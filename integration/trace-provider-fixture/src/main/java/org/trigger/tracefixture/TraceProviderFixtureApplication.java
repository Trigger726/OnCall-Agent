package org.trigger.tracefixture;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@SpringBootApplication
public class TraceProviderFixtureApplication {
    public static void main(String[] args) {
        SpringApplication.run(TraceProviderFixtureApplication.class, args);
    }

    @RestController
    static class ProviderEndpoints {
        @GetMapping("/api/v1/query")
        Map<String, Object> metrics(@RequestParam long time) {
            return Map.of("status", "success", "data", Map.of(
                    "resultType", "vector",
                    "result", List.of(Map.of(
                            "metric", Map.of("__name__", "up", "job", "APP-AUTH"),
                            "value", List.of(time, "1")))));
        }

        @GetMapping("/loki/api/v1/query_range")
        Map<String, Object> logs(@RequestParam long end) {
            return Map.of("status", "success", "data", Map.of(
                    "resultType", "streams",
                    "result", List.of(Map.of(
                            "stream", Map.of("resource_code", "APP-AUTH", "app", "trace-fixture"),
                            "values", List.of(List.of(Long.toString(end), "fixture log sample"))))));
        }
    }
}
