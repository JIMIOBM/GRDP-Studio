package com.grdp.studio.softwareintegration;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationProjectRequest;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationService;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProjectCleanup;
import com.grdp.studio.softwareintegration.support.TemplateCreationJobStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(properties = "grdp.software-integration.dispatcher-enabled=false")
class TemplateCreationProjectLifecycleTests {
    @Autowired SoftwareIntegrationService projects;
    @Autowired TemplateCreationJobStore jobs;
    @Autowired SoftwareIntegrationProjectCleanup cleanup;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private long project() {
        return projects.createProject(new SoftwareIntegrationProjectRequest("template-lifecycle-" + UUID.randomUUID(), null)).id();
    }

    @Test void activeOrUncertainCreationBlocksSoftDeletionUntilConfirmedCancellation() {
        long projectId = project();
        UUID id = UUID.randomUUID();
        jobs.claim(projectId, id, "Well", mapper.createObjectNode().put("a", 1));
        assertDeletionBlocked(projectId);
        jobs.markUncertain(id);
        assertDeletionBlocked(projectId);
        jobs.recover(id, mapper.createObjectNode().put("requestId", id.toString()).put("status", "CANCELLED"));
        projects.deleteProject(projectId);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM software_integration_project WHERE id=?", Boolean.class, projectId)).isTrue();
        // Keep durable UUID/provenance records: recycling does not authorize ID reuse.
        assertThat(jobs.find(id).state()).isEqualTo("CANCELLED");
    }

    @Test void expiredProjectWithUnresolvedCreationIsNotPhysicallyRecycled() {
        long projectId = project();
        UUID id = UUID.randomUUID();
        jobs.claim(projectId, id, "Well", mapper.createObjectNode().put("a", 1));
        jobs.markUncertain(id);
        // Simulate a legacy deletion / operator state existing before the new guard.
        jdbc.update("UPDATE software_integration_project SET deleted_at=? WHERE id=?", LocalDateTime.now().minusYears(1), projectId);
        cleanup.cleanupExpiredProjectCount();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM software_integration_project WHERE id=?", Long.class, projectId)).isEqualTo(1);
        assertThat(jobs.find(id).state()).isEqualTo("UNCERTAIN");
    }

    private void assertDeletionBlocked(long projectId) {
        assertThatThrownBy(() -> projects.deleteProject(projectId)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(409));
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM software_integration_project WHERE id=?", Boolean.class, projectId)).isTrue();
    }
}
