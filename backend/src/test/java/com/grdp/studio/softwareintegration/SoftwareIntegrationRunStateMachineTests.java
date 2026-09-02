package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStateMachine;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoftwareIntegrationRunStateMachineTests {
    @Test
    void permitsOnlyFrozenTransitions() {
        assertThat(SoftwareIntegrationRunStateMachine.allows(
                SoftwareIntegrationRunStatus.QUEUED, SoftwareIntegrationRunStatus.CLAIMED)).isTrue();
        assertThat(SoftwareIntegrationRunStateMachine.allows(
                SoftwareIntegrationRunStatus.CLAIMED, SoftwareIntegrationRunStatus.QUEUED)).isTrue();
        assertThat(SoftwareIntegrationRunStateMachine.allows(
                SoftwareIntegrationRunStatus.COLLECTING, SoftwareIntegrationRunStatus.PARTIAL_SUCCEEDED)).isTrue();
        assertThat(SoftwareIntegrationRunStateMachine.allows(
                SoftwareIntegrationRunStatus.CANCEL_REQUESTED, SoftwareIntegrationRunStatus.SUCCEEDED)).isFalse();
        assertThat(SoftwareIntegrationRunStateMachine.allows(
                SoftwareIntegrationRunStatus.CANCEL_REQUESTED, SoftwareIntegrationRunStatus.PARTIAL_SUCCEEDED)).isFalse();
        assertThatThrownBy(() -> SoftwareIntegrationRunStateMachine.requireAllowed(
                SoftwareIntegrationRunStatus.SUCCEEDED, SoftwareIntegrationRunStatus.FAILED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SoftwareIntegrationRunStateMachine.requireAllowed(
                SoftwareIntegrationRunStatus.QUEUED, SoftwareIntegrationRunStatus.RUNNING_NODAL))
                .isInstanceOf(IllegalStateException.class);
    }
}
