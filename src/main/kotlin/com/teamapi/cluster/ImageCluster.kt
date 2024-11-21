package com.teamapi.cluster

import com.teamapi.dto.Config
import com.teamapi.dto.actor.ActorMessage
import com.teamapi.dto.comfy.QueueRequest
import com.teamapi.dto.comfy.QueueResponse
import com.teamapi.plugins.defaultJson
import com.teamapi.queue.getPosition
import com.teamapi.utils.get
import com.teamapi.utils.int
import com.teamapi.utils.str
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.serialization.json.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ImageCluster(private val cfg: Config, private val callback: () -> Map<String, SendChannel<ActorMessage>>) {
    private val ts = CopyOnWriteArrayList<Pair<String, Boolean>>()
    @OptIn(ExperimentalUuidApi::class)
    private val clientId = Uuid.random().toString().replace("-", "")

    private val client = HttpClient(CIO) {
        install(WebSockets)
        install(ContentNegotiation) {
            json(defaultJson)
        }
    }

    suspend fun destroy() {
        try {
            globalWs.cancelAndJoin()
        } catch (ignored: Exception) {}
    }

    enum class Protocol(val ssl: URLProtocol, val nonSsl: URLProtocol) {
        WEBSOCKET(URLProtocol.WSS, URLProtocol.WS),
        HTTP(URLProtocol.HTTPS, URLProtocol.HTTP),
        ;

    }

    suspend fun queue(obj: JsonObject): QueueResponse {
        val res = client.post("${baseUrl(Protocol.HTTP)}/prompt") {
            setBody(QueueRequest(obj, clientId))
            contentType(ContentType.Application.Json.withCharset(StandardCharsets.UTF_8))
        }
        return res.body<QueueResponse>()
    }

    fun getPosition(id: String) = ts.getPosition(id)

    private fun baseUrl(p: Protocol) = "${(if (cfg.isSSL) p.ssl else p.nonSsl).name}://${cfg.comfyUrl}:${cfg.port}"

    @OptIn(ExperimentalEncodingApi::class, DelicateCoroutinesApi::class)
    private val globalWs = CoroutineScope(Dispatchers.Unconfined).async {
        while (true) {
            try {
                client.webSocket("${baseUrl(Protocol.WEBSOCKET)}/ws?clientId=${clientId}") {
                    val lastId = atomic<String?>(null)
                    incoming
                        .consumeAsFlow()
                        .cancellable()
                        .onCompletion {
                            it?.printStackTrace()
                            println("WS CLOSED??")
                        }
                        .collect {
                            if (it is Frame.Text) {
                                val msg = it.readText()
                                println("${cfg.comfyUrl}: $msg")
                                val thing = Json.parseToJsonElement(msg)
                                when (thing["type"].str()) {
                                    "executing" -> {
                                        if (thing["data"]["node"].str() == "ws_save") {
                                            lastId.value = thing["data"]["prompt_id"].str()
                                        } else if (thing["data"]!!["node"] as? JsonNull != null && lastId.value != null) {
                                            val finishListener = callback()[lastId.value]
                                            if (finishListener?.isClosedForSend == false) {
                                                finishListener.trySend(
                                                    ActorMessage.GenerateResult(
                                                        false,
                                                        error = "Generated, but no image found."
                                                    )
                                                ) // no happen maybe
                                                finishListener.close()
                                            }
                                        }
                                    }
                                    "progress" -> {
                                        // {"type": "progress", "data": {"value": 5, "max": 6, "prompt_id": "9c5ae1af-a554-44f5-bbce-94a70cd863d6", "node": "ef_ksampler_ed"}}
                                        val data = thing["data"]
                                        if (data["node"].str() != "ef_ksampler_ed") return@collect
                                        val promptIdOrNull = data["prompt_id"].str()
                                        promptIdOrNull?.let { id ->
                                            callback()[id]?.send(ActorMessage.ImageProgress(data["value"].int()!!, data["max"]!!.int()!!))
                                        }

                                    }
                                    "status" -> {
                                        val remain = thing["data"]["status"]["exec_info"]["queue_remaining"].int()
                                        if (remain == 0)
                                            cleanup()
                                        updateQueue()
                                    }
                                }
                            } else if (it is Frame.Binary && lastId.value != null) {
                                val finishListener = callback()[lastId.value!!]
                                finishListener?.trySend(
                                    ActorMessage.GenerateResult(
                                        true,
                                        Base64.encode(it.data.drop(8).toByteArray())
                                    )
                                )
                                finishListener?.close()
                                lastId.value = null
                            }
                        }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                println("Cluster died with Exception. trying to reconnect...")
            }
            delay(3000L)
            println("reconnecting...")
        }
    }

    private suspend fun cleanup() {
        println(client.post("${baseUrl(Protocol.HTTP)}/api/easyuse/cleangpu").status)
    }

    private suspend fun updateQueue() {
        val res = client.get {
            url("${baseUrl(Protocol.HTTP)}/queue")
            accept(ContentType.Application.Json)
        }

        val q = res.body<JsonObject>()
        ts.clear()
        ts.addAll(q["queue_running"]!!.jsonArray.mapNotNull { it.jsonArray[1].str()?.let { it to true } })
        ts.addAll(q["queue_pending"]!!.jsonArray.mapNotNull { it.jsonArray[1].str()?.let { it to false } })

        val workers = ts.groupBy({ it.second }) { it.first }

        workers[false]?.let {
            for ((i, t) in it.withIndex()) {
                callback()[t]?.trySend(ActorMessage.QueueUpdate(i))
            }
        }
        workers[true]?.let {
            for (t in it) {
                callback()[t]?.trySend(ActorMessage.QueueUpdate(0)) // working
            }
        }
    }
}
