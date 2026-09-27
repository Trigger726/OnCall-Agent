package org.trigger.opspilot.runbook;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.trigger.opspilot.common.ApiException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public final class PublicationScenarios {
    private PublicationScenarios() { }
    public static RunbookService.ImportCommand command(String key, String body, List<String> roles) {
        return new RunbookService.ImportCommand(key, "APPLICATION", null, "审核手册", "独立复核",
                "acceptance.md", "# 诊断\n" + body, roles);
    }
    public static String key() { return UUID.randomUUID().toString(); }
    public static void verifyConcurrent(RunbookService books, RunbookPublicationService reviews, JdbcClient jdbc) throws Exception {
        String stableKey = "publication-race-" + key();
        var first = books.importMarkdown(command(stableKey, "first pending", List.of("ON_CALL")), 1L).document();
        var second = books.importMarkdown(command(stableKey, "second pending", List.of("ON_CALL")), 1L).document();
        var reviewerKey = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO sys_user(username,password_hash,display_name,role_code,status)
                SELECT :name,password_hash,'独立并发复核人','OPS_MANAGER','ACTIVE' FROM sys_user WHERE id=3
                """).param("name", "review-" + key()).update(reviewerKey, "id");
        long otherReviewer = reviewerKey.getKey().longValue();
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> one = () -> decisionAfterBarrier(reviews, first.id(), 3L, start);
            java.util.concurrent.Callable<String> two = () -> decisionAfterBarrier(reviews, second.id(), otherReviewer, start);
            var a = pool.submit(one); var b = pool.submit(two); start.countDown();
            assertThat(List.of(a.get(20, java.util.concurrent.TimeUnit.SECONDS), b.get(20, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("PUBLISHED", "RUNBOOK_PUBLICATION_BASELINE_CONFLICT");
        } finally { start.countDown(); pool.shutdownNow(); }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM runbook_document WHERE stable_key=:key AND status='PUBLISHED'")
                .param("key", stableKey).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM runbook_document WHERE stable_key=:key AND status='PENDING_REVIEW'")
                .param("key", stableKey).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action='RUNBOOK_PUBLICATION_APPROVE' AND target_id IN (:a,:b)")
                .param("a", Long.toString(first.id())).param("b", Long.toString(second.id())).query(Long.class).single()).isEqualTo(1);
    }

    private static String decisionAfterBarrier(RunbookPublicationService reviews, long id, long actor,
                                               java.util.concurrent.CountDownLatch start) throws Exception {
        if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("publication barrier timeout");
        try { return reviews.decide(id, 0, "APPROVE", key(), "并发独立复核", actor).document().status(); }
        catch (ApiException error) { return error.code(); }
    }

    public static void verifyRollback(RunbookService books, RunbookPublicationService reviews, JdbcClient jdbc,
                                      org.springframework.transaction.PlatformTransactionManager manager) {
        String stableKey = "publication-rollback-" + key();
        var first = books.importMarkdown(command(stableKey, "old published", List.of("ON_CALL")), 1L).document();
        reviews.decide(first.id(), 0, "APPROVE", key(), "原版复核", 3L);
        var candidate = books.importMarkdown(command(stableKey, "new pending", List.of("ON_CALL")), 1L).document();
        var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            reviews.decide(candidate.id(), 0, "APPROVE", key(), "不应提交的审批", 3L);
            throw new IllegalStateException("synthetic outer rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("synthetic outer rollback");
        assertThat(reviews.detail(first.id()).document().status()).isEqualTo("PUBLISHED");
        var after = reviews.detail(candidate.id());
        assertThat(after.document().status()).isEqualTo("PENDING_REVIEW");
        assertThat(after.reviewVersion()).isZero();
        assertThat(after.document().publishedAt()).isNull();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action='RUNBOOK_PUBLICATION_APPROVE' AND target_id=:id")
                .param("id", Long.toString(candidate.id())).query(Long.class).single()).isZero();
    }
    public static void verifyLifecycle(RunbookService books, RunbookPublicationService reviews, JdbcClient jdbc) {
        var draft = books.importMarkdown(command("publication-lifecycle", "cp56uniqueprobe", List.of("ADMIN", "ON_CALL")), 1L).document();
        assertThat(draft.status()).isEqualTo("PENDING_REVIEW");
        assertThat(draft.publishedAt()).isNull();
        assertThat(books.listPublished("ADMIN")).extracting(RunbookService.DocumentView::stableKey).doesNotContain(draft.stableKey());
        assertThat(books.search("cp56uniqueprobe", "ADMIN", 5).results()).isEmpty();
        assertThat(books.searchForUser("cp56uniqueprobe", 2L, 5).results()).isEmpty();
        assertThatThrownBy(() -> books.versions(draft.stableKey(), "ADMIN")).isInstanceOf(ApiException.class);
        String intent = key();
        assertThatThrownBy(() -> reviews.decide(draft.id(), 0, "APPROVE", intent, "确认", 1L))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RUNBOOK_SELF_REVIEW_FORBIDDEN");
        var approved = reviews.decide(draft.id(), 0, "APPROVE", intent, "已复核前置条件与恢复验证", 3L);
        assertThat(approved.document().status()).isEqualTo("PUBLISHED");
        assertThat(approved.document().publishedAt()).isNotNull();
        assertThat(approved.currentPublishedVersion()).isEqualTo(approved.document().versionNo());
        assertThat(books.searchForUser("cp56uniqueprobe", 2L, 5).results()).extracting(RunbookService.SearchResult::stableKey).contains(draft.stableKey());
        var replay = reviews.decide(draft.id(), 0, "APPROVE", intent, "已复核前置条件与恢复验证", 3L);
        assertThat(replay.reviewVersion()).isEqualTo(1);
        assertThat(replay.currentPublishedVersion()).isEqualTo(draft.versionNo());
        assertThatThrownBy(() -> reviews.decide(draft.id(), 0, "APPROVE", intent, "不同说明", 3L))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RUNBOOK_PUBLICATION_KEY_CONFLICT");
        var rejected = books.importMarkdown(command("publication-rejected", "cp56rejectedprobe", List.of("ON_CALL")), 1L).document();
        reviews.decide(rejected.id(), 0, "REJECT", key(), "恢复步骤缺少验证", 3L);
        assertThat(books.searchForUser("cp56rejectedprobe", 2L, 5).results()).isEmpty();
        var withdrawn = books.importMarkdown(command("publication-withdrawn", "cp56withdrawnprobe", List.of("ON_CALL")), 1L).document();
        assertThatThrownBy(() -> reviews.decide(withdrawn.id(), 0, "WITHDRAW", key(), "撤回", 3L))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RUNBOOK_WITHDRAW_NOT_OWNER");
        assertThat(reviews.decide(withdrawn.id(), 0, "WITHDRAW", key(), "补充后另版提交", 1L).document().status()).isEqualTo("WITHDRAWN");
        assertThat(reviews.list("PENDING_REVIEW").items()).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action='RUNBOOK_PUBLICATION_APPROVE'")
                .query(Long.class).single()).isEqualTo(1);
    }
    public static void verifyBaseline(RunbookService books, RunbookPublicationService reviews, JdbcClient jdbc) {
        var first = books.importMarkdown(command("publication-baseline", "cp56original", List.of("ON_CALL")), 1L).document();
        reviews.decide(first.id(), 0, "APPROVE", key(), "初版复核", 3L);
        var second = books.importMarkdown(command(first.stableKey(), "cp56second", List.of("ON_CALL")), 1L).document();
        var stale = books.importMarkdown(command(first.stableKey(), "cp56stale", List.of("ON_CALL")), 1L).document();
        String intent = key();
        reviews.decide(second.id(), 0, "APPROVE", intent, "第二版复核", 3L);
        assertThatThrownBy(() -> reviews.decide(stale.id(), 0, "APPROVE", key(), "旧基线审批", 3L))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RUNBOOK_PUBLICATION_BASELINE_CONFLICT");
        assertThat(reviews.detail(stale.id()).document().status()).isEqualTo("PENDING_REVIEW");
        assertThat(books.versions(first.stableKey(), "ON_CALL")).extracting(RunbookService.DocumentView::status)
                .containsExactly("PUBLISHED", "SUPERSEDED");
        assertThat(books.searchForUser("cp56original", 2L, 5).results()).isEmpty();
        assertThat(books.searchForUser("cp56second", 2L, 5).results()).isNotEmpty();
        var newer = books.importMarkdown(command(first.stableKey(), "cp56newer", List.of("ON_CALL")), 1L).document();
        reviews.decide(newer.id(), 0, "APPROVE", key(), "最新复核", 3L);
        var historicReplay = reviews.decide(second.id(), 0, "APPROVE", intent, "第二版复核", 3L);
        assertThat(historicReplay.document().status()).isEqualTo("SUPERSEDED");
        assertThat(historicReplay.currentPublishedVersion()).isEqualTo(newer.versionNo());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM runbook_document WHERE stable_key=:key AND status='PUBLISHED'")
                .param("key", first.stableKey()).query(Long.class).single()).isEqualTo(1);
    }
    public static void verifyIdentity(RunbookService books, RunbookPublicationService reviews, JdbcClient jdbc) {
        var command = command("publication-identity", "cp56samebody", List.of("ON_CALL", "ADMIN"));
        var first = books.importMarkdown(command, 1L);
        var replay = books.importMarkdown(command("publication-identity", "cp56samebody", List.of("ADMIN", "ON_CALL")), 1L);
        assertThat(replay.reused()).isTrue(); assertThat(replay.document().id()).isEqualTo(first.document().id());
        var changedAcl = books.importMarkdown(command("publication-identity", "cp56samebody", List.of("ADMIN")), 1L);
        assertThat(changedAcl.reused()).isFalse();
        assertThat(changedAcl.document().versionNo()).isEqualTo(2);
        assertThat(books.importMarkdown(command, 3L).reused()).isFalse();
        jdbc.sql("UPDATE sys_user SET role_code='ON_CALL' WHERE id=3").update();
        assertThatThrownBy(() -> reviews.decide(first.document().id(), 0, "APPROVE", key(), "旧JWT不能放行", 3L))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RUNBOOK_PUBLICATION_ROLE_FORBIDDEN");
        assertThatThrownBy(() -> books.importMarkdown(command, 3L)).isInstanceOf(ApiException.class);
    }
}
