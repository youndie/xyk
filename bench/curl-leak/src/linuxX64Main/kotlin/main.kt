import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
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
    env("HEAP_BYTES")?.toLongOrNull()?.takeIf { it > 0 }?.let { GC.maxHeapBytes = it }

    println("curl-leak: engine=$engine target=$target requests=$total readBody=$readBody heapCeiling=${GC.maxHeapBytes}")
    println("request,rss_kB")

    val client =
        when (engine) {
            "curl" -> HttpClient(Curl) { followRedirects = false }
            "cio" -> HttpClient(CIO) { followRedirects = false }
            else -> error("ENGINE is '$engine'; it is curl or cio")
        }
    val body = ByteArray(64) { 'x'.code.toByte() }

    runBlocking {
        for (n in 1..total) {
            val response = client.post(target) { setBody(body) }
            // The status is always touched, so that skipping the body cannot be optimised into
            // skipping the request.
            check(response.status.value in 200..299) { "subscriber answered ${response.status}" }
            if (readBody) response.bodyAsBytes()
            if (n % every == 0) println("$n,${residentKb()}")
        }
    }
    println("done,${residentKb()}")
}

@OptIn(ExperimentalForeignApi::class)
private fun env(name: String): String? = getenv(name)?.toKString()

/** `VmRSS` out of `/proc/self/status`, in kB. Read by hand: there is no dependency here to do it. */
@OptIn(ExperimentalForeignApi::class)
private fun residentKb(): Long {
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
