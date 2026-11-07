package com.n2k02.pbar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryRecoveryPolicyTest {
    @Test fun sameChargingAndSaverExitPolicyAsNetSpeed() {
        assertTrue(BatteryRecoveryPolicy.canRecover(true, true, charging = true, saverEnabled = true))
        assertTrue(BatteryRecoveryPolicy.canRecover(true, true, charging = false, saverEnabled = false))
        assertFalse(BatteryRecoveryPolicy.canRecover(true, true, charging = false, saverEnabled = true))
    }

    @Test fun permissionDenialAndQuitOverridePowerEvents() {
        for (charging in listOf(true, false)) {
            for (saver in listOf(true, false)) {
                assertFalse(BatteryRecoveryPolicy.canRecover(false, true, charging, saver))
                assertFalse(BatteryRecoveryPolicy.canRecover(true, false, charging, saver))
            }
        }
    }

    @Test fun persistedFallbackIntervalMatchesNetSpeed() {
        assertEquals(900_000L, BatteryRecoveryPolicy.FALLBACK_PERIOD_MS)
    }
}
