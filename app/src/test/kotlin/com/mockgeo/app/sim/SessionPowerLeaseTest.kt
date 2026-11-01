package com.mockgeo.app.sim

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SessionPowerLeaseTest {
    @Test fun `running and paused simulation renew bounded lease and idle releases once`() {
        val timeouts = mutableListOf<Long>()
        var releases = 0
        var now = 0L
        val lease = SessionPowerLease({ timeouts.add(it) }, { releases++ }, { now })
        lease.refresh(false)
        assertThat(releases).isEqualTo(0)
        repeat(3) {
            lease.refresh(true)
            lease.refresh(true) // UI updates must not acquire another OS lock every frame.
            now += SessionPowerLease.RENEW_MS
        }
        assertThat(timeouts).containsExactly(60_000L, 60_000L, 60_000L)
        lease.refresh(false)
        lease.close()
        assertThat(releases).isEqualTo(1)
    }

    @Test fun `service destruction releases lease and a later session acquires again`() {
        var acquisitions = 0
        var releases = 0
        val lease = SessionPowerLease({ acquisitions++ }, { releases++ })
        lease.refresh(true)
        lease.close()
        lease.refresh(true)
        lease.close()
        assertThat(acquisitions).isEqualTo(2)
        assertThat(releases).isEqualTo(2)
    }
}
