package com.cutm.nt14.gateway.routes

import com.cutm.nt14.gateway.core.HandlerStartNsKey
import com.cutm.nt14.gateway.core.PolyLanceUpstream
import com.cutm.nt14.gateway.core.UpstreamMsKey
import com.cutm.nt14.gateway.core.UpstreamParseMsKey
import com.cutm.nt14.gateway.core.UpstreamProtocolKey
import com.cutm.nt14.gateway.core.UpstreamTelemetryCallback
import com.cutm.nt14.gateway.models.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private val logger = LoggerFactory.getLogger("DemoRoutes")
private val dynamicLocalEscrows = CopyOnWriteArrayList<PolyLanceEscrow>()

private fun ApplicationCall.recordUpstream(): UpstreamTelemetryCallback = { ms, proto, parseMs ->
    attributes.put(UpstreamMsKey, ms)
    attributes.put(UpstreamProtocolKey, proto)
    attributes.put(UpstreamParseMsKey, parseMs)
    if (!response.isCommitted) {
        runCatching {
            val startNs = attributes.getOrNull(HandlerStartNsKey)
            val totalSoFar = if (startNs != null) ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(ms + parseMs) else (ms + parseMs)
            response.headers.append("X-Upstream-Ms", ms.toString(), safeOnly = false)
            response.headers.append("X-Upstream-Protocol", proto, safeOnly = false)
            response.headers.append("X-Parse-Ms", parseMs.toString(), safeOnly = false)
            response.headers.append("X-Total-Ms", totalSoFar.toString(), safeOnly = false)
        }
    }
}

fun Route.demoRoutes(upstream: PolyLanceUpstream = PolyLanceUpstream()) {
    route("/api") {
        get("/users") {
            try {
                val liveTalents = upstream.fetchTalents(call.recordUpstream())
                call.respond(liveTalents)
            } catch (e: Exception) {
                logger.error("Failed to fetch live talents/users from PolyLance upstream: ${e.message}")
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to (e.message ?: "Upstream unavailable")))
            }
        }

        get("/orders") {
            try {
                val liveEscrows = upstream.fetchEscrows(call.recordUpstream())
                val combined = dynamicLocalEscrows.toList() + liveEscrows
                call.respond(combined)
            } catch (e: Exception) {
                logger.error("Failed to fetch live escrows/orders from PolyLance upstream: ${e.message}")
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to (e.message ?: "Upstream unavailable")))
            }
        }

        get("/products") {
            try {
                val liveAttestations = upstream.fetchAttestations(call.recordUpstream())
                call.respond(liveAttestations)
            } catch (e: Exception) {
                logger.error("Failed to fetch live attestations from PolyLance upstream: ${e.message}")
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to (e.message ?: "Upstream unavailable")))
            }
        }

        // Real-Time PolyLance Sovereign Protocol Endpoints
        route("/polylance") {
            get("/health") {
                val ok = upstream.keepAlive(call.recordUpstream())
                if (ok) {
                    call.respond(HttpStatusCode.OK, ApiMessage("PolyLance upstream healthy (${upstream.baseUrl}, protocol=${upstream.lastProtocol})"))
                } else {
                    call.respond(HttpStatusCode.BadGateway, ApiMessage("PolyLance upstream unreachable (${upstream.baseUrl})"))
                }
            }

            get("/escrows") {
                try {
                    val liveEscrows = upstream.fetchEscrows(call.recordUpstream())
                    // Combine locally posted test escrows with live production PolyLance escrows
                    val combined = dynamicLocalEscrows.toList() + liveEscrows
                    call.respond(combined)
                } catch (e: Exception) {
                    logger.error("Failed to fetch live escrows from PolyLance upstream: ${e.message}", e)
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ApiMessage("PolyLance upstream error: ${e.message}")
                    )
                }
            }

            post("/escrows") {
                val req = try {
                    call.receive<CreateEscrowRequest>()
                } catch (e: Exception) {
                    CreateEscrowRequest()
                }
                val newId = "0x" + UUID.randomUUID().toString().replace("-", "").take(12)
                val newEscrow = PolyLanceEscrow(
                    escrowId = newId,
                    title = "Test Escrow via NT14 Gateway",
                    client = req.client.ifBlank { "0xb30F2eFBCEBC529d946e05C9ccE0f1ffFB7e1aB1" },
                    freelancer = req.freelancer.ifBlank { "0xB8aa0398B91A150B041DA819bc954Bb356e009Dd" },
                    amountPol = if (req.amountPol > 0) req.amountPol else 500.0,
                    token = "POL",
                    status = "Funded",
                    contractAddress = "0x" + UUID.randomUUID().toString().replace("-", "").take(40),
                    createdAt = System.currentTimeMillis()
                )
                dynamicLocalEscrows.add(0, newEscrow)
                call.respond(HttpStatusCode.Created, newEscrow)
            }

            get("/attestations") {
                try {
                    val liveAttestations = upstream.fetchAttestations(call.recordUpstream())
                    call.respond(liveAttestations)
                } catch (e: Exception) {
                    logger.error("Failed to fetch live attestations from PolyLance upstream: ${e.message}", e)
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ApiMessage("PolyLance upstream error: ${e.message}")
                    )
                }
            }

            get("/talents") {
                try {
                    val liveTalents = upstream.fetchTalents(call.recordUpstream())
                    call.respond(liveTalents)
                } catch (e: Exception) {
                    logger.error("Failed to fetch live talents from PolyLance upstream: ${e.message}", e)
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ApiMessage("PolyLance upstream error: ${e.message}")
                    )
                }
            }
        }
    }
}
