package com.teamapi.plugins

import com.teamapi.dto.Config
import com.teamapi.dto.GenerateRequest
import com.teamapi.dto.GenerateResponse
import com.teamapi.dto.comfy.QueueRequest
import com.teamapi.dto.comfy.QueueResponse
import com.teamapi.utils.editChild
import com.teamapi.utils.get
import com.teamapi.utils.str
import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.webjars.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.CopyOnWriteArraySet
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random


operator fun Rectangle2D.component1(): Double = width
operator fun Rectangle2D.component2(): Double = height

@OptIn(ExperimentalSerializationApi::class)
val config get() = Json.decodeFromStream<Config>(ClassLoader.getSystemResourceAsStream("config.json")!!)

@OptIn(ExperimentalEncodingApi::class, ExperimentalSerializationApi::class)
fun Application.configureRouting() {
    install(Webjars) {
        path = "/webjars" //defaults to /webjars
    }
    install(ContentNegotiation) {
        json()
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

    val queue = CopyOnWriteArraySet<String>()

    val client = HttpClient(CIO) {
        install(WebSockets)
        install(ClientContentNegotiation) {
            json()
        }
    }

    val defaultFont = Font.createFont(
        Font.TRUETYPE_FONT,
        ClassLoader.getSystemResourceAsStream("Pretendard-Regular.otf")!!
    ).deriveFont(128f)
    val clientId = UUID.randomUUID().toString().replace("-", "")

    val ts = CopyOnWriteArraySet<String>()
//    var currentRunning: String? = null

    val coroutine = CoroutineScope(Dispatchers.Unconfined).async {
        val cfg = config
        while (true) {
            if (queue.isEmpty()) {
                delay(100L)
                continue
            }

            val res = client.get {
                url("${(if (cfg.isSSL) URLProtocol.HTTPS else URLProtocol.HTTP).name}://${cfg.comfyUrl}:${cfg.port}/queue")
                accept(ContentType.Application.Json)
            }

            val q = res.body<JsonObject>()
            ts.clear()
            ts.addAll(q["queue_pending"]!!.jsonArray.mapNotNull { it.jsonArray[1].str() })
            ts.addAll(q["queue_running"]!!.jsonArray.mapNotNull { it.jsonArray[1].str() })

            delay(1000L)
//            currentRunning = q["queue_running"][0]?.jsonArray?.get(1).str()
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
         runBlocking {
             coroutine.cancelAndJoin()
         }
    })

    routing {
        post("/gen") {
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

            var promptId: String? = null
            val cfg = config

            val queued = async {
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
                promptId = res.body<QueueResponse>().promptId
            }

            queued.invokeOnCompletion {
                queue.add(promptId)
            }

            client.webSocket("${(if (cfg.isSSL) URLProtocol.WSS else URLProtocol.WS).name}://${cfg.comfyUrl}:${cfg.port}/ws?clientId=${clientId}") {
                val timeout = launch {
                    var count = 0
                    while (this@webSocket.isActive) {
                        delay(1000L)
                        if (promptId != null && !ts.contains(promptId)) {
                            if (count++ < 3) continue
                            close()
                            queue.remove(promptId)
                            try {
                                this@post.call.respond(
                                    GenerateResponse(
                                        false,
                                        error = "image not generated; maybe image cached?"
                                    )
                                )
                            } catch (ignored: Exception) {}
                            cancel()
                            break
                        }
                    }
                }
                var lastId: String? = null
                incoming.consumeAsFlow().cancellable().collect {
                    if (it is Frame.Text) {
                        val msg = it.readText()

                        val thing = Json.parseToJsonElement(msg)
                        if (promptId != null && thing["type"].str() == "executing" && thing["data"]["node"].str() == "ws_save" && thing["data"]["prompt_id"].str() == promptId) {
                            lastId = thing["data"]["prompt_id"].str()
                        }
                    } else if (it is Frame.Binary && promptId != null && lastId == promptId) {
                        this@post.call.respond(GenerateResponse(true, Base64.encode(it.data.drop(8).toByteArray())))
                        try {
                            queue.remove(promptId)
                        } catch (ignore: Exception) {}
                        try {
                            timeout.cancel()
                        } catch (ignore: Exception) {}
                        try {
                            close()
                        } catch (ignore: Exception) {}
                    }
                }
                queued.cancelAndJoin() // this will not happen... maybe?
            }
        }

        get("/webjars") {
            call.respondText("<script src='/webjars/jquery/jquery.js'></script>", ContentType.Text.Html)
        }
    }
}
