package util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

class ConnectErrorsTest {

    @Test
    fun `names the host the resolver could not resolve`() {
        val android = UnknownHostException("Unable to resolve host \"oauth2.googleapis.com\": No address associated with hostname")
        assertEquals(
            "no_dns; oauth2.googleapis.com could not be resolved — check the network DNS",
            android.connectMessage(),
        )
    }

    @Test
    fun `still says something useful when the resolver message has no host`() {
        val message = UnknownHostException("Name or service not known").connectMessage()
        assertTrue(message.startsWith("no_dns;"), message)
        assertTrue(message.contains("network DNS"), message)
    }

    @Test
    fun `finds the failure inside a wrapper`() {
        // OkHttp and coroutines routinely hand the UI something that merely wraps the cause.
        val wrapped = IOException("request failed", UnknownHostException("lite.xdp.es"))
        assertEquals(
            "no_dns; lite.xdp.es could not be resolved — check the network DNS",
            wrapped.connectMessage(),
        )
    }

    @Test
    fun `distinguishes the other connection failures`() {
        assertEquals("timeout; the sign-in server did not answer", SocketTimeoutException("read timed out").connectMessage())
        assertTrue(ConnectException("Connection refused").connectMessage().startsWith("no_connection;"))
        assertTrue(SSLHandshakeException("PKIX path building failed").connectMessage().startsWith("tls_error;"))
    }

    @Test
    fun `keeps a specific message it does not recognise`() {
        // An OAuth error_description is more useful than anything generic we could write.
        assertEquals("invalid_grant: bad code", Exception("invalid_grant: bad code").connectMessage())
        assertEquals("login failed", Exception().connectMessage())
    }
}
