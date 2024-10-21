package com.teamapi.plugins

import com.teamapi.cluster.ImageCluster
import com.teamapi.dto.Config
import com.teamapi.dto.GenerateRequest
import com.teamapi.dto.actor.ActorMessage
import com.teamapi.dto.ws.QueueInfoMessage
import com.teamapi.utils.editChild
import io.github.smiley4.ktorswaggerui.SwaggerUI
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
import io.ktor.websocket.Frame
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.awt.*
import java.awt.font.TextLayout
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random


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

@OptIn(ExperimentalEncodingApi::class, ExperimentalSerializationApi::class, DelicateCoroutinesApi::class)
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

    install(WebSockets) {
        timeoutMillis = -1
    }


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

    routing {
        webSocket("/ws") {
            println("hi")
            val pId = call.request.queryParameters["prompt"] ?: return@webSocket close()
            println("pid received: $pId")
            val workingCluster = clusters.map { it.getPosition(pId) }.find { it != -1 } ?: return@webSocket close()
            println("cluster found! position: $workingCluster")

            val initMsg = defaultJson.encodeToString(QueueInfoMessage(workingCluster))
            outgoing.trySend(Frame.Text(initMsg))

            callbackFlow {
                callback[pId] = this
                awaitClose { callback.remove(pId) }
            }.collect {
                if (outgoing.isClosedForSend) {
                    return@collect // wait
                }
                val msg = defaultJson.encodeToString(it.data)
                outgoing.trySend(Frame.Text(msg))
            }

            println("finalize")
            close()
        }
        post("/gen/sdxl") {
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

            val queueResult = clusters.random().queue(JsonObject(prompt)) // TODO: prioritized queue
            call.respond(queueResult)
        }

        post("/gen/flux") {
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

                graphic.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
                graphic.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                graphic.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
                graphic.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE)
                graphic.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                graphic.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphic.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                graphic.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

                val transform = graphic.transform
                transform.translate(body.width / 2 - fontWidth / 2, drawPos)
                graphic.transform(transform)
                graphic.color = Color.WHITE
                val tl = TextLayout(body.title, graphic.font, graphic.fontRenderContext)
                val shape = tl.getOutline(null)
                graphic.stroke = BasicStroke(2f)

                graphic.draw(shape)
                graphic.color = Color.BLACK
                graphic.fill(shape)
            }

            val maskImage = ByteArrayOutputStream().use {
                ImageIO.write(buf, "png", it)
                Base64.encode(it.toByteArray())
            }

            val base = String(ClassLoader.getSystemResourceAsStream("flux_prompt.json")!!.readAllBytes())
            val prompt = Json.parseToJsonElement(base).jsonObject.toMutableMap()
            val seed = JsonPrimitive(Random.nextLong().toULong())

            prompt.editChild("prompt") {
                editChild("inputs") {
                    set("t5xxl", JsonPrimitive(body.prompt))
                }
            }
            prompt.editChild("ef_loader_ed") {
                editChild("inputs") {
                    set("image_width", JsonPrimitive(body.width))
                    set("image_height", JsonPrimitive(body.height))
                    set("seed", seed)
                }
            }
            prompt.editChild("ef_ksampler_ed") {
                editChild("inputs") {
                    set("seed", seed)
                }
            }
            prompt.editChild("cnet_mask_loader") {
                editChild("inputs") {
                    set("base64_data", JsonPrimitive(maskImage))
                }
            }

            val queueResult = clusters.random().queue(JsonObject(prompt)) // TODO: prioritized queue
            call.respond(queueResult)
        }

        get("/webjars") {
            call.respondText("<script src='/webjars/jquery/jquery.js'></script>", ContentType.Text.Html)
        }
    }
}
