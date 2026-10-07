package dev.netherforge.plugin.api

import dev.netherforge.format.project.Requirement
import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.http.HttpCall
import dev.netherforge.plugin.http.HttpFetcher
import dev.netherforge.plugin.http.RequestRate
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.session.ProjectSession
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * `nf.http`: requests to web services.
 *
 * What a script got wrong in the call (a URL that isn't http or https, a
 * host its package didn't declare, a header that can't be sent, a body over
 * the limit) is an error at its line, found on the main thread before
 * anything starts. What only the network can say, or the owner's limits (a
 * name that doesn't resolve, an address that isn't allowed, a timeout, the
 * rate, a redirect to an undeclared host), is the work's failure: `nil, err`.
 * The request runs on a worker ([HttpFetcher]); its redirects' hosts are held
 * to the calling package's declaration with the check bound here, on the
 * main thread, to the package whose code made the call.
 */
internal class NfHttpImpl(private val session: ProjectSession) : NfHttpApi {
    private val limits get() = session.config.http
    private val rate by lazy { RequestRate(limits.requestsPerMinute) }

    override fun request(caller: Caller, options: HttpRequestOptions): CompletionStage<HttpResponse> {
        val url = try {
            URI(options.url)
        } catch (e: URISyntaxException) {
            throw LuaApiException("options.url isn't a valid URL: ${e.reason}")
        }
        val host = HttpFetcher.hostOf(url)
            ?: throw LuaApiException("options.url must be an http:// or https:// URL with a host (got \"${options.url}\")")
        if (url.rawUserInfo !=
            null
        ) {
            throw LuaApiException("options.url can't have a user name or password in it: send credentials in a header")
        }
        if (HttpFetcher.portOf(url) !in 1..MAX_PORT) throw LuaApiException("options.url has a port that isn't one")
        val method = options.method ?: "GET"
        val body = options.body?.toByteArray(StandardCharsets.UTF_8)
        if (body != null && method in NO_BODY) throw LuaApiException("a $method request can't have a body")
        if (body != null && body.size > limits.maxRequestBytes) {
            throw LuaApiException(
                "options.body is ${body.size} bytes, over the server's limit of ${limits.maxRequestBytes} (http.max-request-bytes in config.yml)"
            )
        }
        val headers = headers(options.headers.orEmpty())
        // The narrow check: this host, for the package whose code is running. Redirects are held to the same one, bound to it here.
        session.requirements.check(Requirement.Http(host), "nf.http.request")
        val held = session.requirements.held("nf.http.request")
        if (!rate.tryStart(caller.scope.namespace)) {
            return CompletableFuture.failedFuture(
                WorkFailed(
                    "package \"${caller.scope.namespace}\" has made ${limits.requestsPerMinute} requests in the last minute, " +
                        "the server's limit (http.requests-per-minute in config.yml)"
                )
            )
        }
        val call = HttpCall(method, url, headers, body)
        val fetcher = session.http.fetcher
        return session.async.workers.submit {
            val reply = fetcher.fetch(call) { redirectedTo -> held(Requirement.Http(redirectedTo)) }
            HttpResponse(reply.status.toLong(), reply.headers, reply.body, reply.url.toString())
        }
    }

    /** [given] as headers that can be sent as they are: names that are tokens, values of printable ASCII (and tabs), none the server sets itself, none twice. */
    private fun headers(given: Map<String, String>): Map<String, String> {
        val seen = HashSet<String>()
        for ((name, value) in given) {
            if (!TOKEN.matches(name)) throw LuaApiException("options.headers has a name that isn't a header name: \"$name\"")
            if (!seen.add(name.lowercase())) throw LuaApiException("options.headers has \"$name\" twice (names are compared without case)")
            if (name.lowercase() in SERVER_SET) throw LuaApiException("options.headers can't set \"$name\": the server sets it")
            if (value.any { it.code > 0x7e || (it.code < 0x20 && it != '\t') || it.code == 0x7f }) {
                throw LuaApiException("options.headers[\"$name\"] has a character a header can't hold (only printable ASCII and tabs)")
            }
        }
        return given
    }

    private companion object {
        const val MAX_PORT = 65535
        val NO_BODY = setOf("GET", "HEAD", "OPTIONS")
        val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
        val SERVER_SET = setOf(
            "host", "content-length", "transfer-encoding", "connection", "upgrade", "expect", "te", "trailer",
            "keep-alive", "proxy-connection", "accept-encoding"
        )
    }
}
