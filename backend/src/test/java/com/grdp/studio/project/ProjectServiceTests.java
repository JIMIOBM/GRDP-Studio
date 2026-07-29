package com.grdp.studio.project;

import com.grdp.studio.project.dto.ProjectSaveRequest;
import com.grdp.studio.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ProjectServiceTests {

    @Autowired
    private ProjectService projectService;

    @Test
    void createsAndReadsProject() {
        var created = projectService.createProject(
                new ProjectSaveRequest("测试项目", "MyBatis-Plus 集成测试")
        );

        assertThat(created.id()).isNotNull();
        assertThat(projectService.getProject(created.id()).name()).isEqualTo("测试项目");
    }
}
