package com.n2k02.pbar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PBarPowerRecoveryPolicyTest {
    @Test fun chargingResumesEvenWhenSaverIsOn() {
        assertTrue(PBarRecoveryPolicy.shouldRestore(enabled = true, quitRequested = false, charging = true, powerSaveMode = true))
    }

    @Test fun disablingSaverResumesWhileUnplugged() {
        assertTrue(PBarRecoveryPolicy.shouldRestore(enabled = true, quitRequested = false, charging = false, powerSaveMode = false))
    }

    @Test fun saverWithoutChargingDefers() {
        assertFalse(PBarRecoveryPolicy.shouldRestore(enabled = true, quitRequested = false, charging = false, powerSaveMode = true))
    }

    @Test fun manualQuitAlwaysOverridesRecovery() {
        assertFalse(PBarRecoveryPolicy.shouldRestore(enabled = true, quitRequested = true, charging = true, powerSaveMode = false))
        assertFalse(PBarRecoveryPolicy.shouldRestore(enabled = true, quitRequested = true, charging = true, powerSaveMode = true))
    }

    @Test fun disabledOverlayNeverRestarts() {
        assertFalse(PBarRecoveryPolicy.shouldRestore(enabled = false, quitRequested = false, charging = true, powerSaveMode = false))
    }
}
