package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.controller.SoftwareEnvironmentController;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SoftwareEnvironmentControllerTests {
    @Test void hostLocalOperatorCanConfigure() {
        assertTrue(SoftwareEnvironmentController.isLocalConfigurationRequest("127.0.0.1", "http://localhost:5173", "1"));
        assertTrue(SoftwareEnvironmentController.isLocalConfigurationRequest("0:0:0:0:0:0:0:1", "http://[::1]:5173", "1"));
    }
    @Test void remoteAndCrossOriginExecutableChangesAreRejected() {
        assertFalse(SoftwareEnvironmentController.isLocalConfigurationRequest("192.168.1.2", "http://localhost:5173", "1"));
        assertFalse(SoftwareEnvironmentController.isLocalConfigurationRequest("127.0.0.1", "https://evil.example", "1"));
        assertFalse(SoftwareEnvironmentController.isLocalConfigurationRequest("127.0.0.1", "null", "1"));
        assertFalse(SoftwareEnvironmentController.isLocalConfigurationRequest("127.0.0.1", "http://localhost:5173", null));
    }
}
