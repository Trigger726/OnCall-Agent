package org.example.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.AlertRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 公司告警手册控制器
 * 提供告警规则的查询、搜索、分类等功能
 */
@RestController
@RequestMapping("/api/alert-handbook")
public class AlertHandbookController {

    private static final Logger logger = LoggerFactory.getLogger(AlertHandbookController.class);

    private List<AlertRule> alertRules;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AlertHandbookController() {
        loadAlertRules();
    }

    /**
     * 加载告警手册数据
     */
    private synchronized void loadAlertRules() {
        try {
            ClassPathResource resource = new ClassPathResource("alert-handbook.json");
            try (InputStream inputStream = resource.getInputStream()) {
                alertRules = objectMapper.readValue(inputStream,
                        new TypeReference<List<AlertRule>>() {});
                logger.info("成功加载告警手册，共 {} 条规则", alertRules.size());
            }
        } catch (Exception e) {
            logger.error("加载告警手册失败", e);
            alertRules = new ArrayList<>();
        }
    }

    /**
     * 获取所有告警规则
     */
    @GetMapping("/all")
    public ResponseEntity<Map<String, Object>> getAllAlerts() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", alertRules);
        result.put("total", alertRules.size());
        return ResponseEntity.ok(result);
    }

    /**
     * 按严重级别筛选
     */
    @GetMapping("/by-severity/{severity}")
    public ResponseEntity<Map<String, Object>> getBySeverity(@PathVariable String severity) {
        List<AlertRule> filtered = alertRules.stream()
                .filter(a -> a.getSeverity().equalsIgnoreCase(severity))
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", filtered);
        result.put("total", filtered.size());
        return ResponseEntity.ok(result);
    }

    /**
     * 按分类筛选
     */
    @GetMapping("/by-category/{category}")
    public ResponseEntity<Map<String, Object>> getByCategory(@PathVariable String category) {
        List<AlertRule> filtered = alertRules.stream()
                .filter(a -> a.getCategory().equals(category))
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", filtered);
        result.put("total", filtered.size());
        return ResponseEntity.ok(result);
    }

    /**
     * 搜索告警（按名称、描述、分类匹配）
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchAlerts(@RequestParam("q") String keyword) {
        String lowerKeyword = keyword.toLowerCase();
        List<AlertRule> filtered = alertRules.stream()
                .filter(a ->
                        a.getName().toLowerCase().contains(lowerKeyword) ||
                        a.getDescription().toLowerCase().contains(lowerKeyword) ||
                        a.getCategory().toLowerCase().contains(lowerKeyword))
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", filtered);
        result.put("total", filtered.size());
        return ResponseEntity.ok(result);
    }

    /**
     * 获取单条告警详情
     */
    @GetMapping("/detail/{id}")
    public ResponseEntity<Map<String, Object>> getAlertDetail(@PathVariable String id) {
        Optional<AlertRule> alert = alertRules.stream()
                .filter(a -> a.getId().equalsIgnoreCase(id))
                .findFirst();

        Map<String, Object> result = new HashMap<>();
        if (alert.isPresent()) {
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", alert.get());
        } else {
            result.put("code", 404);
            result.put("message", "告警规则未找到");
            result.put("data", null);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 获取所有告警分类
     */
    @GetMapping("/categories")
    public ResponseEntity<Map<String, Object>> getCategories() {
        Set<String> categories = alertRules.stream()
                .map(AlertRule::getCategory)
                .collect(Collectors.toSet());

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", categories);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取告警统计摘要
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        Map<String, Object> summary = new HashMap<>();

        // 按严重级别统计
        long criticalCount = alertRules.stream().filter(a -> "critical".equals(a.getSeverity())).count();
        long warningCount = alertRules.stream().filter(a -> "warning".equals(a.getSeverity())).count();
        long infoCount = alertRules.stream().filter(a -> "info".equals(a.getSeverity())).count();

        Map<String, Long> severityStats = new HashMap<>();
        severityStats.put("critical", criticalCount);
        severityStats.put("warning", warningCount);
        severityStats.put("info", infoCount);
        summary.put("bySeverity", severityStats);

        // 按分类统计
        Map<String, Long> categoryStats = alertRules.stream()
                .collect(Collectors.groupingBy(AlertRule::getCategory, Collectors.counting()));
        summary.put("byCategory", categoryStats);
        summary.put("totalAlerts", alertRules.size());

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", summary);
        return ResponseEntity.ok(result);
    }

    /**
     * 重新加载告警手册（热更新）
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        loadAlertRules();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "告警手册已重新加载，共 " + alertRules.size() + " 条规则");
        result.put("data", alertRules.size());
        return ResponseEntity.ok(result);
    }
}
