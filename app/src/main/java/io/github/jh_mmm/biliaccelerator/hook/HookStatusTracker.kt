package io.github.jh_mmm.biliaccelerator.hook

import java.util.concurrent.ConcurrentHashMap

object HookStatusTracker {
    private val statusMap = ConcurrentHashMap<String, String>()

    fun recordStatus(component: String, detail: String) {
        statusMap[component] = detail
    }

    fun getSnapshot(): Map<String, String> = HashMap(statusMap)

    fun formatReport(): String {
        val sb = StringBuilder()
        sb.appendLine("Application Context: ${statusMap["app_context"] ?: "未捕获"}")
        sb.appendLine("Player Segment Builder: ${statusMap["player_segment"] ?: "未挂载"}")
        sb.appendLine("Player Fallback: ${statusMap["player_fallback"] ?: "未挂载"}")
        sb.appendLine("Moss gRPC Helper: ${statusMap["moss_grpc"] ?: "未挂载"}")
        return sb.toString().trimEnd()
    }
}
