package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfilePinHashTest {
    @Test fun savingOtherProfileFieldsKeepsExistingPinValid() {
        val stored = NetflixViewModel.hashPin("4832")
        assertEquals(stored, NetflixViewModel.hashPin(stored))
        assertNotEquals(stored, NetflixViewModel.hashPin("4833"))
    }
}
