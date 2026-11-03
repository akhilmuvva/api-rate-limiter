package com.cutm.nt14.gateway.core

import com.cutm.nt14.gateway.models.PolyLanceAttestation
import com.cutm.nt14.gateway.models.PolyLanceEscrow
import com.cutm.nt14.gateway.models.PolyLanceTalent
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

val HandlerStartNsKey = AttributeKey<Long>("HandlerStartNs")
val UpstreamMsKey = AttributeKey<Long>("UpstreamMs")
val UpstreamProtocolKey = AttributeKey<String>("UpstreamProtocol")
val UpstreamParseMsKey = AttributeKey<Long>("UpstreamParseMs")

typealias UpstreamTelemetryCallback = (upstreamMs: Long, protocol: String, parseMs: Long) -> Unit

class PolyLanceUpstreamException(message: String) : Exception(message)

/**
 * Live client for the production PolyLance Zenith backend.
 *
 * Uses a single process-wide long-lived java.net.http.HttpClient configured with HTTP/2
 * multiplexing, TLS session resumption, keep-alive connection pooling, and gzip compression.
 */
class PolyLanceUpstream(
    val baseUrl: String = System.getenv("POLYLANCE_API_BASE")
        ?: System.getenv("POLYLANCE_BASE_URL")
        ?: "https://polylance-fv-1-45wy.onrender.com"
) {
    companion object {
        val sharedHttpClient: HttpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    @Volatile var lastProtocol: String = "HTTP_2"
        private set
    val totalUpstreamCalls = AtomicLong(0)

    fun upstreamUrlFor(gatewayPath: String): String? = when (gatewayPath) {
        "/api/polylance/escrows", "/api/orders" -> "$baseUrl/api/jobs"
        "/api/polylance/talents", "/api/users" -> "$baseUrl/api/sync"
        "/api/polylance/attestations", "/api/products" -> "$baseUrl/api/sync"
        "/api/polylance/health" -> "$baseUrl/health"
        else -> null
    }
    private val logger = LoggerFactory.getLogger(PolyLanceUpstream::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun fetchJson(path: String, onUpstream: UpstreamTelemetryCallback? = null): JsonElement {
        val url = "$baseUrl$path"
        val startNs = System.nanoTime()
        val req = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "application/json")
            .header("Accept-Encoding", "gzip")
            .header("User-Agent", "NT14-Gateway/1.0")
            .GET()
            .build()

        val resp = try {
            sharedHttpClient.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).await()
        } catch (e: Exception) {
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            onUpstream?.invoke(elapsedMs, lastProtocol, 0L)
            logger.warn("PolyLance upstream unreachable: $url -> ${e.message}")
            throw PolyLanceUpstreamException("PolyLance upstream unreachable: ${e.message}")
        }
        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        val proto = resp.version().name
        lastProtocol = proto
        totalUpstreamCalls.incrementAndGet()

        val status = resp.statusCode()
        if (status !in 200..299) {
            onUpstream?.invoke(elapsedMs, proto, 0L)
            throw PolyLanceUpstreamException("PolyLance upstream returned HTTP $status for $path")
        }
        val parseStartNs = System.nanoTime()
        val rawBytes = resp.body()
        val isGzip = resp.headers().firstValue("Content-Encoding").orElse("").equals("gzip", ignoreCase = true)
        val body = if (isGzip && rawBytes.isNotEmpty()) {
            GZIPInputStream(ByteArrayInputStream(rawBytes)).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } else {
            String(rawBytes, StandardCharsets.UTF_8)
        }
        val parsed = json.parseToJsonElement(body)
        val parseMs = ((System.nanoTime() - parseStartNs) / 1_000_000L).coerceAtLeast(0L)
        onUpstream?.invoke(elapsedMs, proto, parseMs)
        return parsed
    }

    /**
     * Periodic health probe to keep Render upstream instance warm and prevent cold-start sleeps.
     */
    suspend fun keepAlive(onUpstream: UpstreamTelemetryCallback? = null): Boolean {
        val startNs = System.nanoTime()
        return try {
            val req = HttpRequest.newBuilder(URI("$baseUrl/health"))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "NT14-Gateway/1.0")
                .GET()
                .build()
            val resp = sharedHttpClient.sendAsync(req, HttpResponse.BodyHandlers.discarding()).await()
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val proto = resp.version().name
            lastProtocol = proto
            totalUpstreamCalls.incrementAndGet()
            onUpstream?.invoke(elapsedMs, proto, 0L)
            resp.statusCode() in 200..299
        } catch (e: Exception) {
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            onUpstream?.invoke(elapsedMs, lastProtocol, 0L)
            logger.debug("PolyLance keepAlive notice: ${e.message}")
            false
        }
    }

    /** Live escrow jobs from PolyLance `/api/jobs`. */
    suspend fun fetchEscrows(onUpstream: UpstreamTelemetryCallback? = null): List<PolyLanceEscrow> {
        val root = fetchJson("/api/jobs", onUpstream).jsonObject
        val jobs = root["jobs"] as? JsonArray ?: return emptyList()
        return jobs.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val token = o.str("paymentTokenSymbol") ?: "POL"
            // USDC jobs carry the value in amountUsdc; native-token jobs in amountEth
            val amount = if (token.equals("USDC", true)) {
                o.str("amountUsdc")?.toDoubleOrNull()
            } else {
                o.str("amountEth")?.toDoubleOrNull()
            } ?: 0.0
            PolyLanceEscrow(
                escrowId = o.str("id") ?: return@mapNotNull null,
                title = o.str("title")?.trim().orEmpty(),
                client = o.str("client").orEmpty(),
                freelancer = o.str("freelancer").orEmpty(),
                amountPol = amount,
                token = token,
                status = o.str("status") ?: "Unknown",
                contractAddress = o.str("contractAddress"),
                createdAt = o.str("createdAt")?.toLongOrNull()
            )
        }.sortedByDescending { it.createdAt ?: 0L }
    }

    private suspend fun fetchProfiles(onUpstream: UpstreamTelemetryCallback? = null): List<JsonObject> {
        val root = fetchJson("/api/sync", onUpstream).jsonObject
        val profiles = root["profiles"] as? JsonObject ?: return emptyList()
        return profiles.values.mapNotNull { it as? JsonObject }
    }

    /** GitHub-verified skill attestations derived from live PolyLance profiles. */
    suspend fun fetchAttestations(onUpstream: UpstreamTelemetryCallback? = null): List<PolyLanceAttestation> {
        return fetchProfiles(onUpstream)
            .filter { it.str("githubVerified") == "true" && !it.str("githubUsername").isNullOrBlank() }
            .map { p ->
                val tier = p.str("reputationTier") ?: "UNRATED"
                val category = p.str("primaryCategory") ?: "general"
                val score = p.str("primaryScore") ?: "0"
                PolyLanceAttestation(
                    attestationId = p.str("address").orEmpty(),
                    developerGithub = p.str("githubUsername").orEmpty(),
                    skillAttestation = "$tier $category (score $score)",
                    soulboundTokenId = "${p.str("reputationSbtCount") ?: "0"} SBT"
                )
            }
    }

    /** Talent directory from live PolyLance profiles, highest score first. */
    suspend fun fetchTalents(onUpstream: UpstreamTelemetryCallback? = null): List<PolyLanceTalent> {
        return fetchProfiles(onUpstream)
            .filter { it.str("role") != "client" }
            .map { p ->
                val skills = (p["skills"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.take(3)
                    .orEmpty()
                val title = p.str("title")?.takeIf { it.isNotBlank() }
                    ?: p.str("primaryCategory")?.replaceFirstChar { it.uppercase() }
                    ?: "Freelancer"
                PolyLanceTalent(
                    talentId = p.str("address").orEmpty(),
                    name = p.str("displayName")?.takeIf { it.isNotBlank() }
                        ?: p.str("githubUsername")
                        ?: p.str("address").orEmpty(),
                    specialization = if (skills.isEmpty()) title else "$title - ${skills.joinToString(", ")}",
                    rating = p.str("primaryScore")?.toDoubleOrNull() ?: 0.0
                )
            }
            .sortedByDescending { it.rating }
    }

    private fun JsonObject.str(key: String): String? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        return (v as? JsonPrimitive)?.contentOrNull
    }
}
