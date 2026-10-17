package io.github.jh_mmm.biliaccelerator.hook

import java.util.concurrent.ConcurrentHashMap

object HookStatusTracker {
    private val statusMap = ConcurrentHashMap<String, String>()

    fun recordStatus(component: String, detail: String) {
        statusMap[component] = detail
    }

    fun updateFromMap(map: Map<String, String>) {
        statusMap.putAll(map)
    }

    fun getSnapshot(): Map<String, String> = HashMap(statusMap)

    fun formatReport(snapshot: Map<String, String> = statusMap): String {
        val map = if (snapshot.isNotEmpty()) snapshot else statusMap
        val sb = StringBuilder()
        sb.appendLine("Application Context: ${map["app_context"] ?: "未捕获"}")
        sb.appendLine("Player Segment Builder: ${map["player_segment"] ?: "未挂载"}")
        sb.appendLine("Player Fallback: ${map["player_fallback"] ?: "未挂载"}")
        sb.appendLine("Moss gRPC Helper: ${map["moss_grpc"] ?: "未挂载"}")
        return sb.toString().trimEnd()
    }
}
