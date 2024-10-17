package com.teamapi.plugins

import com.teamapi.cluster.ImageCluster
import com.teamapi.dto.Config
import com.teamapi.dto.GenerateRequest
import com.teamapi.dto.actor.ActorMessage
import com.teamapi.dto.comfy.QueueRequest
import com.teamapi.dto.comfy.QueueResponse
import com.teamapi.utils.editChild
import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.webjars.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

operator fun Rectangle2D.component1(): Double = width
operator fun Rectangle2D.component2(): Double = height

@OptIn(ExperimentalSerializationApi::class)
val config get() = Json.decodeFromStream<Config>(ClassLoader.getSystemResourceAsStream("config.json")!!)

@OptIn(ExperimentalSerializationApi::class)
val defaultJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    allowSpecialFloatingPointValues = true
    encodeDefaults = true // might need this also
    classDiscriminator = "_serverClass"
    classDiscriminatorMode = ClassDiscriminatorMode.NONE
}

@OptIn(ExperimentalEncodingApi::class, ExperimentalSerializationApi::class)
fun Application.configureRouting() {
    install(Webjars) {
        path = "/webjars" //defaults to /webjars
    }
    install(ContentNegotiation) {
        json(defaultJson)
    }
    install(SwaggerUI) {
        swagger {
            swaggerUrl = "swagger-ui"
            forwardRoot = true
        }
        info {
            title = "Example API"
            version = "latest"
            description = "Example API for testing and demonstration purposes."
        }
        server {
            url = "http://localhost:8080"
            description = "Development Server"
        }
    }

    install(WebSockets)


    val defaultFont = Font.createFont(
        Font.TRUETYPE_FONT,
        ClassLoader.getSystemResourceAsStream("Pretendard-Regular.otf")!!
    ).deriveFont(128f)
    val callback = ConcurrentHashMap<String, SendChannel<ActorMessage>>()
    val clusters = listOf(
        ImageCluster(config) { callback }
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        callback.forEach {
            it.value.trySend(
                ActorMessage.GenerateResult(
                    false,
                    error = "Server is closing."
                )
            )
        }

        runBlocking {
            clusters.forEach {
                it.destroy()
            }
        }
    })


    val client = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(defaultJson)
        }
    }

    routing {
        webSocket("/ws") {
            val pId = call.request.queryParameters["prompt"] ?: return@webSocket close()

            callbackFlow {
                callback[pId] = this
                awaitClose { callback.remove(pId) }
            }.onCompletion {
                if (this@webSocket.isActive) this@webSocket.close()
            }.collect {
                val msg = defaultJson.encodeToString(it.data)
                outgoing.trySend(Frame.Text(msg))
            }

            outgoing.invokeOnClose {
                it?.printStackTrace()
                callback[pId]?.close()
                println("closed")
            }
        }
        post("/gen") {
            val clientId = UUID.randomUUID().toString().replace("-", "")

            val body = this.call.receive<GenerateRequest>()

            val buf = BufferedImage(body.width, body.height, BufferedImage.TYPE_INT_RGB).apply {
                val graphic = graphics as Graphics2D
                graphic.background = Color.BLACK
                graphic.font = defaultFont

                val (fontWidth, fontHeight) = graphic.fontMetrics.getStringBounds(body.title, graphic)
                val drawPos = when (body.pos) {
                    0 -> fontHeight
                    1 -> body.height / 2 + fontHeight / 2
                    else -> body.height - fontHeight / 2
                }

                graphic.color = Color.WHITE
                graphic.drawString(body.title, (body.width / 2 - fontWidth / 2).toInt(), drawPos.toInt())
            }

            val maskImage = ByteArrayOutputStream().use {
                ImageIO.write(buf, "png", it)
                Base64.encode(it.toByteArray())
            }

            val base = String(ClassLoader.getSystemResourceAsStream("prompt.json")!!.readAllBytes())
            val prompt = Json.parseToJsonElement(base).jsonObject.toMutableMap()
            prompt.editChild("positive_node") {
                editChild("inputs") {
                    set("text", JsonPrimitive(body.prompt))
                }
            }
            prompt.editChild("initial_image") {
                editChild("inputs") {
                    set("width", JsonPrimitive(body.width))
                    set("height", JsonPrimitive(body.height))
                }
            }
            prompt.editChild("run_t2i") {
                editChild("inputs") {
                    set("seed", JsonPrimitive(Random.nextLong().toULong()))
                }
            }
            prompt.editChild("mask_image_loader") {
                editChild("inputs") {
                    set("base64_data", JsonPrimitive(maskImage))
                }
            }

            val cfg = config

            val res = client.post {
                url {
                    host = cfg.comfyUrl
                    port = cfg.port
                    path("prompt")
                    protocol = if (cfg.isSSL) URLProtocol.HTTPS else URLProtocol.HTTP
                }
                setBody(QueueRequest(JsonObject(prompt), clientId))
                contentType(ContentType.Application.Json.withCharset(StandardCharsets.UTF_8))
            }

            call.respond(res.body<QueueResponse>())
        }

        get("/webjars") {
            call.respondText("<script src='/webjars/jquery/jquery.js'></script>", ContentType.Text.Html)
        }
    }
}
