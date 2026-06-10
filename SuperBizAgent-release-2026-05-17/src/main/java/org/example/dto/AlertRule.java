package org.example.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

/**
 * 告警手册规则条目
 */
@Getter
@Setter
public class AlertRule {
    /** 告警编号 */
    private String id;
    /** 告警名称 */
    private String name;
    /** 分类: 资源/服务/性能/数据库/中间件/安全/网络/容器/任务/数据 */
    private String category;
    /** 严重级别: critical/warning/info */
    private String severity;
    /** 告警描述 */
    private String description;
    /** 可能原因 */
    private List<String> possibleCauses;
    /** 排查步骤 */
    private List<String> diagnosticSteps;
    /** 处理方案 */
    private List<String> resolutionSteps;
    /** 升级规则 */
    private String escalationRule;
    /** 关联文档 */
    private List<String> relatedDocs;
}
