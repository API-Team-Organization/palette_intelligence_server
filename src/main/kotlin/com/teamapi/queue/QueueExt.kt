package com.teamapi.queue

fun List<Pair<String, Boolean>>.getPosition(id: String): Int? {
    val workers = groupBy({ it.second }) { it.first }

    workers[false]?.withIndex()?.find { it.value == id }?.index?.let { i ->
        return i + 1
    }
    workers[true]?.let {
        if (id in it) return 0
    }
    return null
}
