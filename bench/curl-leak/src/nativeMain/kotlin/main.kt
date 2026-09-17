import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/**
 * The minimal reproducer for [B-30](../../../../docs/backlog/B-30-delivery-memory-growth.md).
 *
 * xyk's delivery half grows about 3.2 kB of anonymous memory per delivery, outside a managed heap
 * that is pinned, and an arm that removed the request removed the growth. That arm still had the
 * whole service around it — SQLite, chronik timers, four workers, a Ktor server taking ingest — so
 * "it is the request" was an attribution by subtraction rather than a thing anybody had seen on its
 * own. This is the thing on its own: one client, one loop, no database, no server.
 *
 * Two knobs, because the service arm could not separate them:
 *
 * * `READ_BODY=0` skips `bodyAsBytes()`, which is the only response handling xyk does. If the
 *   growth survives that, reading the response is not the cost.
 * * `ENGINE=cio` swaps the curl engine for the one other client engine native has. It speaks
 *   plain HTTP only, which this loop does anyway, and it is the control that separates the
 *   engine from ktor's client core.
 * * `HEAP_BYTES` pins `GC.maxHeapBytes` the way the service does, so that "the managed heap is not
 *   where this lives" is shown here too rather than carried over.
 *
 * It prints `VmRSS` from `/proc/self/status` — resident memory of this process and nothing else,
 * which is the point of having no service around it.
 */
@OptIn(NativeRuntimeApi::class, ExperimentalForeignApi::class)
fun main() {
    val target = env("TARGET") ?: "http://127.0.0.1:9101/hook"
    val total = env("REQUESTS")?.toIntOrNull() ?: 20_000
    val every = env("EVERY")?.toIntOrNull() ?: 1_000
    val readBody = env("READ_BODY") != "0"
    val engine = env("ENGINE") ?: "curl"
    // The arm that asks whether this is a leak at all. A Kotlin/Native object costs a few
    // hundred bytes of heap and may hold kilobytes of native memory behind it; the GC counts
    // the heap and cannot see the rest, so it has no reason to run. `GC_EVERY=1000` gives it
    // one. If resident memory stops growing, nothing is leaking — it is being retained.
    val gcEvery = env("GC_EVERY")?.toIntOrNull() ?: 0
    // The arm that separates "this leaks" from "this is held for the life of the client".
    // Everything else here keeps one client for the whole run, which is what the service does
    // and what any long-lived caller does. If closing it gives the memory back, the growth
    // belongs to the client's lifetime and a caller can end it; if it does not, nothing a
    // caller does will.
    val clientEvery = env("CLIENT_EVERY")?.toIntOrNull() ?: 0
    // TWO KNOBS FOR "LESS LOAD, MORE TIME". `DELAY_MS` spaces the requests out, which asks
    // whether the cost is per request or per second; `IDLE_SECONDS` keeps the process alive
    // and quiet afterwards, which asks whether anything is given back when nothing is
    // happening. A deployment that delivers once a second for a week is the case neither the
    // soak nor the first reproducer covered.
    val delayMs = env("DELAY_MS")?.toLongOrNull() ?: 0L
    val idleSeconds = env("IDLE_SECONDS")?.toIntOrNull() ?: 0
    env("HEAP_BYTES")?.toLongOrNull()?.takeIf { it > 0 }?.let { GC.maxHeapBytes = it }

    println("curl-leak: engine=$engine target=$target requests=$total readBody=$readBody gcEvery=$gcEvery clientEvery=$clientEvery delayMs=$delayMs heapCeiling=${GC.maxHeapBytes}")
    println("request,rss_kB")

    fun newClient() =
        when (engine) {
            "curl" ->
                HttpClient(Curl) {
                    followRedirects = false
                    // The HTTPS arm points at a sink with a self-signed certificate. Verifying
                    // it would measure certificate plumbing; the question is whether TLS changes
                    // the growth, so the check is turned off rather than the CA arranged.
                    engine { sslVerify = env("SSL_VERIFY") != "0" }
                }
            "cio" -> HttpClient(CIO) { followRedirects = false }
            else -> error("ENGINE is '$engine'; it is curl or cio")
        }

    var client = newClient()
    val body = ByteArray(64) { 'x'.code.toByte() }

    runBlocking {
        for (n in 1..total) {
            val response = client.post(target) { setBody(body) }
            // The status is always touched, so that skipping the body cannot be optimised into
            // skipping the request.
            check(response.status.value in 200..299) { "subscriber answered ${response.status}" }
            if (readBody) response.bodyAsBytes()
            if (clientEvery > 0 && n % clientEvery == 0) {
                client.close()
                client = newClient()
            }
            if (gcEvery > 0 && n % gcEvery == 0) GC.collect()
            if (n % every == 0) println("$n,${residentKb()}")
            if (delayMs > 0) delay(delayMs)
        }
    }
    println("done,${residentKb()}")

    // Sampled while idle, then once more after a forced collection: "nothing is given back" and
    // "nothing is given back until something asks" are different answers.
    if (idleSeconds > 0) {
        runBlocking {
            var waited = 0
            while (waited < idleSeconds) {
                delay(15_000)
                waited += 15
                println("idle+${waited}s,${residentKb()}")
            }
        }
        GC.collect()
        println("idle+gc,${residentKb()}")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun env(name: String): String? = getenv(name)?.toKString()

/**
 * Resident memory in kB, from `/proc` where there is one and from `ps` where there is not.
 *
 * macOS has no `/proc/self/status`, and the first version of this returned `-1` there for a whole
 * arm before anybody noticed — a reproducer that reports `-1` rather than failing is a reproducer
 * that produces a table of nothing.
 */
@OptIn(ExperimentalForeignApi::class)
private fun residentKb(): Long {
    val fromProc = residentKbFromProc()
    return if (fromProc > 0) fromProc else residentKbFromPs()
}

/** `ps -o rss=` on this process. Slower and fine: it is called once every few thousand requests. */
@OptIn(ExperimentalForeignApi::class)
private fun residentKbFromPs(): Long {
    val pipe = platform.posix.popen("ps -o rss= -p ${platform.posix.getpid()}", "r") ?: return -1
    try {
        val buffer = ByteArray(64)
        val line = platform.posix.fgets(buffer.refTo(0), buffer.size, pipe)?.toKString() ?: return -1
        return line.trim().toLongOrNull() ?: -1
    } finally {
        platform.posix.pclose(pipe)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun residentKbFromProc(): Long {
    val file = platform.posix.fopen("/proc/self/status", "r") ?: return -1
    try {
        val buffer = ByteArray(512)
        while (true) {
            val line = platform.posix.fgets(buffer.refTo(0), buffer.size, file)?.toKString() ?: return -1
            if (line.startsWith("VmRSS:")) {
                return line.filter { it.isDigit() }.toLongOrNull() ?: -1
            }
        }
    } finally {
        platform.posix.fclose(file)
    }
}
