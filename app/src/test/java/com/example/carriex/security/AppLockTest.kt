package com.example.carriex.security

import org.junit.Assert.*
import org.junit.Test

class AppLockTest {
    @Test fun fourCharactersAreAcceptedAndShorterPasswordsAreRejected() {
        val password = "1234".toCharArray()
        assertTrue(PasswordVerifier.matches(password, PasswordVerifier.create(password)))
        assertTrue(runCatching { PasswordVerifier.create("123".toCharArray()) }.isFailure)
    }

    @Test fun passwordIsSaltedAndWrongOrCorruptValuesNeverMatch() {
        val password = "correct horse battery".toCharArray()
        val record = PasswordVerifier.create(password)
        assertTrue(PasswordVerifier.matches(password, record))
        assertFalse(PasswordVerifier.matches("incorrect horse".toCharArray(), record))
        assertNotEquals(record, PasswordVerifier.create(password))
        assertFalse(record.contains("correct"))
        listOf("", record.replace("SHA256", "MD5"), record.replace("1:", "2:"), record + ":extra",
            record.substringBeforeLast(':') + ":ab").forEach { assertFalse(PasswordVerifier.matches(password, it)) }
    }

    @Test fun oldestAndroidPasswordAlgorithmIsSupported() {
        val password = "six or more".toCharArray()
        val record = PasswordVerifier.create(password, PasswordVerifier.Algorithm.SHA1)
        assertTrue(PasswordVerifier.matches(password, record))
    }

    @Test fun newProcessIsLockedButRecreationAndLaunchOnlyPolicyPreserveUnlock() {
        val session = LockSession()
        assertTrue(session.needsUnlock(true))
        assertFalse(session.needsUnlock(false))
        session.unlock()
        session.background(true, true, changingConfiguration = true)
        assertFalse(session.needsUnlock(true))
        session.background(true, false, changingConfiguration = false)
        assertFalse(session.needsUnlock(true))
        session.background(true, true, changingConfiguration = false)
        assertTrue(session.needsUnlock(true))
        assertTrue(LockSession().needsUnlock(true))
    }

    @Test fun lockingInvalidatesAuthenticationAttemptsAlreadyInFlight() {
        val session = LockSession()
        session.unlock()
        val pendingAttempt = session.generation
        session.lock()
        assertNotEquals(pendingAttempt, session.generation)
        assertFalse(session.authenticated)
    }
}
