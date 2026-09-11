package util

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Turns a connection failure into something the user can act on.
 *
 * The raw exceptions are useless in the UI: `Unable to resolve host "oauth2.googleapis.com":
 * No address associated with hostname` tells a phone owner nothing, and it is the one case
 * that is usually theirs to fix — Android's Private DNS in strict mode fails *every* lookup
 * when the resolver it points at does not answer, so the whole device looks offline at once.
 *
 * Anything unrecognised keeps its own message, which is at least specific to the operation
 * that failed (an OAuth `error_description`, for instance).
 */
fun Throwable.connectMessage(): String {
    val cause = generateSequence(this) { it.cause }.firstOrNull { it.isConnectionFailure() }
    return when (cause) {
        is UnknownHostException -> {
            val host = hostIn(cause.message)
            "no_dns; ${host ?: "the sign-in host"} could not be resolved — check the network DNS"
        }
        is SocketTimeoutException -> "timeout; the sign-in server did not answer"
        is ConnectException, is NoRouteToHostException -> "no_connection; the sign-in server could not be reached"
        is SSLException -> "tls_error; the secure connection to the sign-in server failed"
        else -> message?.take(MAX_LENGTH) ?: "login failed"
    }
}

private fun Throwable.isConnectionFailure(): Boolean =
    this is UnknownHostException ||
        this is SocketTimeoutException ||
        this is ConnectException ||
        this is NoRouteToHostException ||
        this is SSLException

/** The hostname out of a resolver message; a `null` answer still leaves a usable sentence. */
private fun hostIn(message: String?): String? =
    message?.let { HOST_REGEX.find(it)?.value }

private const val MAX_LENGTH = 140
private val HOST_REGEX = Regex("""[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+""", RegexOption.IGNORE_CASE)
