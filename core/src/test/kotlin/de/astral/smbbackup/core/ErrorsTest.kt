package de.astral.smbbackup.core

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

class ErrorsTest {
    /** So meldet smbj eine ausbleibende Antwort beim Verbindungsaufbau. */
    private val smbjTimeout = ExecutionException(SMBRuntimeException(TimeoutException("Timeout expired")))

    @Test
    fun timeoutsAndDroppedConnectionsAreTransient() {
        assertTrue(isTransientConnectionError(smbjTimeout))
        assertTrue(isTransientConnectionError(TransportException("Connection reset")))
        assertTrue(isTransientConnectionError(ConnectException("Connection refused")))
    }

    @Test
    fun serverAnswersAreNotTransient() {
        val logonFailure = SMBApiException(NtStatus.STATUS_LOGON_FAILURE.value, null, null, null)
        assertFalse(isTransientConnectionError(logonFailure))
        assertFalse(isTransientConnectionError(SmbBackupException("keine Verschlüsselung")))
        assertFalse(isTransientConnectionError(UnknownHostException("nas")))
        assertFalse(isTransientConnectionError(IllegalStateException("Fehler")))
    }

    @Test
    fun timeoutHasReadableMessage() {
        assertTrue(describeError(smbjTimeout).startsWith("Zeitüberschreitung"))
    }
}
