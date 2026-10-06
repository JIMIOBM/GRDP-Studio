package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.TemplateCreationJobStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class TemplateCreationJobStoreTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private TemplateCreationJobStore store;
    private final UUID id = UUID.randomUUID();

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE software_integration_project (id BIGINT PRIMARY KEY, deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE software_integration_model (id BIGINT PRIMARY KEY, project_id BIGINT, deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE software_integration_model_version (id BIGINT PRIMARY KEY, model_id BIGINT, sha256 CHAR(64))");
        jdbc.update("INSERT INTO software_integration_project (id) VALUES (1),(2)");
        jdbc.update("INSERT INTO software_integration_model (id,project_id) VALUES (1,1),(2,2)");
        store = new TemplateCreationJobStore(jdbc, mapper); store.run(null);
    }

    private ObjectNode inputs() { return mapper.createObjectNode().put("a", 1).put("b", 2); }
    private ObjectNode successful() {
        var node = mapper.createObjectNode().put("requestId", id.toString()).put("status", "SUCCEEDED");
        node.putObject("model").put("sha256", "a".repeat(64)); return node;
    }
    private void ready() { store.claim(1, id, "Well", inputs()); store.recover(id, successful()); }

    @Test void canonicalClaimSurvivesNewStoreInstanceAndRejectsRebinding() {
        assertThat(store.claim(1, id, "Well", inputs()).newlyClaimed()).isTrue();
        var restarted = new TemplateCreationJobStore(jdbc, mapper);
        assertThat(restarted.claim(1, id, "Well", mapper.createObjectNode().put("b", 2).put("a", 1)).newlyClaimed()).isFalse();
        assertThatThrownBy(() -> store.claim(2, id, "Well", inputs())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.claim(1, id, "Other", inputs())).isInstanceOf(IllegalStateException.class);
    }

    @Test void deletedProjectAndInvalidIdCannotClaim() {
        jdbc.update("UPDATE software_integration_project SET deleted_at=CURRENT_TIMESTAMP WHERE id=1");
        assertThatThrownBy(() -> store.claim(1, id, "Well", inputs())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.claim(2, new UUID(0, 0), "Well", inputs())).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.find(id)).isNull();
    }

    @Test void projectQuotaIsDurableAndExistingClaimCanStillBeRecovered() {
        assertThat(store.claim(1, id, "Well", inputs(), 1).newlyClaimed()).isTrue();
        assertThat(store.claim(1, id, "Well", inputs(), 1).newlyClaimed()).isFalse();
        assertThatThrownBy(() -> store.claim(1, UUID.randomUUID(), "Other", inputs(), 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("上限");
        assertThat(store.claim(2, UUID.randomUUID(), "Other", inputs(), 1).newlyClaimed()).isTrue();
    }

    @Test void sharedListIsProjectBoundedMetadataOnlyAndStableOnRestart() {
        store.claim(1, id, "SharedWell", inputs());
        store.claim(2, UUID.randomUUID(), "OtherProjectWell", inputs());
        for (int index = 0; index < 51; index++) store.claim(1, UUID.randomUUID(), "Well" + index, inputs());
        var restarted = new TemplateCreationJobStore(jdbc, mapper);
        var records = restarted.list(1);
        assertThat(records).hasSize(50).allMatch(record -> record.projectId() == 1);
        assertThat(restarted.list(2)).extracting(TemplateCreationJobStore.Summary::well).containsExactly("OtherProjectWell");
        assertThat(restarted.list(3)).isEmpty();
        assertThat(mapper.writeValueAsString(records)).doesNotContain("fingerprint", "request_json", "workerRecord", "inputs");
        assertThat(restarted.list(1)).isEqualTo(records);
    }

    @Test void deletionProtectionIncludesUnknownStatesUntilConfirmedTerminal() {
        store.claim(1, id, "Well", inputs());
        assertThat(TemplateCreationJobStore.hasUnresolvedJobs(jdbc, 1)).isTrue();
        store.markUncertain(id);
        assertThat(TemplateCreationJobStore.hasUnresolvedJobs(jdbc, 1)).isTrue();
        jdbc.update("UPDATE software_integration_template_creation SET state='FUTURE_STATE' WHERE request_id=?", id.toString());
        assertThat(TemplateCreationJobStore.hasUnresolvedJobs(jdbc, 1)).isTrue();
        for (String state : java.util.List.of("SUCCEEDED", "FAILED", "INTERRUPTED", "CANCELLED", "TIMED_OUT")) {
            jdbc.update("UPDATE software_integration_template_creation SET state=? WHERE request_id=?", state, id.toString());
            assertThat(TemplateCreationJobStore.hasUnresolvedJobs(jdbc, 1)).isFalse();
        }
        assertThat(TemplateCreationJobStore.hasUnresolvedJobs(jdbc, 2)).isFalse();
    }

    @Test void rejectedClaimIsImmutableAndDoesNotBlockDeletionOrAuthorizeNewPost() {
        store.claim(1, id, "Well", inputs()); store.markRejected(id); store.markUncertain(id);
        assertThat(store.find(id).state()).isEqualTo("REJECTED");
        assertThat(store.find(id).workerRecord()).isNull();
        assertThat(TemplateCreationJobStore.hasUnresolvedJobs(jdbc, 1)).isFalse();
        assertThat(store.claim(1, id, "Well", inputs()).newlyClaimed()).isFalse();
        assertThatThrownBy(() -> store.recover(id, successful())).isInstanceOf(IllegalStateException.class);
    }

    @Test void uncertainClaimIsRecoveredWithoutDispatchAndTerminalRecordIsImmutable() {
        store.claim(1, id, "Well", inputs()); store.markUncertain(id);
        assertThat(store.find(id).state()).isEqualTo("UNCERTAIN");
        store.recover(id, successful()); store.markUncertain(id);
        assertThat(store.find(id).state()).isEqualTo("SUCCEEDED");
        store.recover(id, successful());
        assertThatThrownBy(() -> store.recover(id, successful().put("status", "FAILED"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.recover(id, successful().put("requestId", UUID.randomUUID().toString()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void registrationRunsOnceAndKeepsSourceBinding() {
        ready(); var count = new AtomicInteger();
        assertThat(store.register(id, 1, job -> {
            count.incrementAndGet(); jdbc.update("INSERT INTO software_integration_model_version VALUES (10,1,?)", "a".repeat(64)); return 10L;
        })).isEqualTo(10);
        assertThat(store.register(id, 1, job -> { throw new AssertionError("must not upload twice"); })).isEqualTo(10);
        assertThat(count.get()).isEqualTo(1); assertThat(store.find(id).versionId()).isEqualTo(10);
        assertThatThrownBy(() -> store.register(id, 2, job -> 10L)).isInstanceOf(IllegalStateException.class);
    }

    @Test void hashMismatchRollsBackCallbackAndVersionBinding() {
        ready();
        assertThatThrownBy(() -> store.register(id, 1, job -> {
            jdbc.update("INSERT INTO software_integration_model_version VALUES (10,1,?)", "b".repeat(64)); return 10L;
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM software_integration_model_version", Long.class)).isZero();
        assertThat(store.find(id).versionId()).isNull();
    }

    @Test void crossProjectVersionCannotBeRegistered() {
        ready(); jdbc.update("INSERT INTO software_integration_model_version VALUES (10,2,?)", "a".repeat(64));
        assertThatThrownBy(() -> store.register(id, 1, job -> 10L)).isInstanceOf(IllegalStateException.class);
        assertThat(store.find(id).versionId()).isNull();
    }

    @Test void preparingCannotBeRegistered() {
        store.claim(1, id, "Well", inputs());
        assertThatThrownBy(() -> store.register(id, 1, job -> { throw new AssertionError(); })).isInstanceOf(IllegalStateException.class);
    }

    @Test void concurrentRegistrarsUploadExactlyOnce() throws Exception {
        ready(); var count = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Long> register = () -> new TemplateCreationJobStore(jdbc, mapper).register(id, 1, job -> {
                count.incrementAndGet(); jdbc.update("INSERT INTO software_integration_model_version VALUES (10,1,?)", "a".repeat(64)); return 10L;
            });
            var first = pool.submit(register); var second = pool.submit(register);
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(10); assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(10);
        }
        assertThat(count.get()).isEqualTo(1);
    }
}
