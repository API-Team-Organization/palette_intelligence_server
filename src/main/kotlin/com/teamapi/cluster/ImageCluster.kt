package com.teamapi.cluster

import com.teamapi.dto.Config
import com.teamapi.dto.actor.ActorMessage
import com.teamapi.plugins.defaultJson
import com.teamapi.utils.get
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class ImageCluster(private val cfg: Config, private val callback: () -> Map<String, SendChannel<ActorMessage>>) {
    private val ts = CopyOnWriteArrayList<Pair<String, Boolean>>()

    private val client = HttpClient(CIO) {
        install(WebSockets)
        install(ContentNegotiation) {
            json(defaultJson)
        }
    }

    suspend fun destroy() {
        globalWs.cancelAndJoin()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private val globalWs = CoroutineScope(Dispatchers.Unconfined).async {
        client.webSocket("${(if (cfg.isSSL) URLProtocol.WSS else URLProtocol.WS).name}://${cfg.comfyUrl}:${cfg.port}/ws") {
            val lastId = atomic<String?>(null)
            incoming.consumeAsFlow().cancellable().collect {
                if (it is Frame.Text) {
                    val msg = it.readText()

                    val thing = Json.parseToJsonElement(msg)
                    if (thing["type"].str() == "executing") {
                        if (thing["data"]["node"].str() == "ws_save") {
                            lastId.value = thing["data"]["prompt_id"].str()
                        } else if (thing["data"]!!["node"] as? JsonNull != null) {
                            val finishListener = callback()[lastId.value!!]
                            finishListener?.trySend(
                                ActorMessage.GenerateResult(
                                    false,
                                    error = "Generated, but no image found."
                                )
                            ) // no happen maybe
                            finishListener?.close()
                        }
                    } else if (thing["type"].str() == "status") {
                        updateQueue()
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
    }

    private suspend fun updateQueue() {
        val res = client.get {
            url("${(if (cfg.isSSL) URLProtocol.HTTPS else URLProtocol.HTTP).name}://${cfg.comfyUrl}:${cfg.port}/queue")
            accept(ContentType.Application.Json)
        }

        val q = res.body<JsonObject>()
        ts.clear()
        ts.addAll(q["queue_running"]!!.jsonArray.mapNotNull { it.jsonArray[1].str()?.let { it to true } })
        ts.addAll(q["queue_pending"]!!.jsonArray.mapNotNull { it.jsonArray[1].str()?.let { it to false } })

        val workers = ts.groupBy({ it.second }) { it.first }

        for ((i, t) in workers[false]!!.withIndex()) {
            callback()[t]?.trySend(ActorMessage.QueueUpdate(i))
        }
        for (t in workers[true]!!) {
            callback()[t]?.trySend(ActorMessage.QueueUpdate(0)) // working
        }
    }
}
