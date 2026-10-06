package io.github.jh_mmm.biliaccelerator.hook

import android.net.Uri
import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.libxposed.api.XposedModule

object PlayerHook {

    private const val TAG = "BiliAccelerator-Player"

    // Both spellings are observed across different Bilibili versions (Assert vs Asset)
    private val SEGMENT_BUILDER_CLASSES = listOf(
        "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaAssertSegment\$Builder",
        "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaAssetSegment\$Builder",
        "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaSegment\$Builder"
    )

    fun init(module: XposedModule, classLoader: ClassLoader) {
        var hooked = false
        for (className in SEGMENT_BUILDER_CLASSES) {
            val builderClass = try {
                classLoader.loadClass(className)
            } catch (_: Throwable) {
                null
            } ?: continue

            hookSegmentBuilder(module, builderClass)
            hooked = true
            Log.i(TAG, "Successfully hooked player segment builder: $className")
        }

        // Fallback: Hook IjkMediaPlayer directly if segment builder is missing or bypassed
        hookFallbackPlayer(module, classLoader)

        if (!hooked) {
            Log.i(TAG, "Standard IjkMediaAsset builder not found, relying on player fallback hooks")
        }
    }

    private fun hookSegmentBuilder(module: XposedModule, clazz: Class<*>) {
        // 1. Hook all constructors
        for (constructor in clazz.declaredConstructors + clazz.constructors) {
            try {
                module.hook(constructor).intercept { chain ->
                    rewriteArgIfMedia(chain.args)
                    chain.proceed()
                }
            } catch (t: Throwable) {
                Log.d(TAG, "Could not hook constructor: ${t.message}")
            }
        }

        // 2. Hook setter methods: setUrl, setBaseUrl, setResolveUrl
        val setterNames = setOf("setUrl", "setBaseUrl", "setResolveUrl")
        val methods = (clazz.declaredMethods + clazz.methods).distinct()
        for (m in methods) {
            if (m.name in setterNames) {
                try {
                    module.hook(m).intercept { chain ->
                        rewriteArgIfMedia(chain.args)
                        chain.proceed()
                    }
                } catch (_: Throwable) {}
            }
        }

        // 3. Hook setBackupUrls(Collection/List or Array)
        val backupMethods = (clazz.declaredMethods + clazz.methods).distinct().filter {
            it.name == "setBackupUrls" && it.parameterTypes.size == 1
        }

        for (method in backupMethods) {
            try {
                module.hook(method).intercept { chain ->
                    val arg = chain.args.firstOrNull() ?: return@intercept chain.proceed()
                    val rawList: List<String> = when (arg) {
                        is Collection<*> -> arg.filterIsInstance<String>()
                        is Array<*> -> arg.filterIsInstance<String>()
                        else -> return@intercept chain.proceed()
                    }
                    if (rawList.isEmpty()) return@intercept chain.proceed()

                    val config = RemoteClient.fetchConfig()
                    if (!config.enabled || !config.blockPcdn) return@intercept chain.proceed()

                    val target = config.targetHost
                    val filtered = mutableListOf<String>()

                    for (url in rawList) {
                        val res = BiliAcceleratorCore.rewriteUrl(url, config)
                        if (res.changed) {
                            RemoteClient.notifyRewrite(res)
                            filtered.add(res.finalUrl)
                        } else if (!res.isPcdn) {
                            filtered.add(url)
                        }
                    }

                    // Ensure we have backup mirrors in case the primary fails
                    if (filtered.isEmpty() && target.isNotBlank()) {
                        val first = rawList.firstOrNull()
                        if (first != null) {
                            for (candidate in BiliAcceleratorCore.CANDIDATE_POOL.take(3)) {
                                if (candidate != target) {
                                    val backupRes = BiliAcceleratorCore.rewriteUrl(first, config.copy(targetHost = candidate, forceUpos = true))
                                    if (backupRes.changed) {
                                        filtered.add(backupRes.finalUrl)
                                    }
                                }
                            }
                        }
                    }

                    // 只有在过滤出有效非空地址时才写入；若为空则保持原参数不动，防止播放器失去备用流或 NPE
                    if (filtered.isNotEmpty()) {
                        val paramType = method.parameterTypes[0]
                        (chain.args as? MutableList<Any?>)?.set(0, when {
                            paramType.isArray -> filtered.toTypedArray()
                            java.util.Set::class.java.isAssignableFrom(paramType) -> filtered.toSet()
                            else -> filtered
                        })
                    }
                    chain.proceed()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to hook ${method.name}: ${t.message}")
            }
        }
    }

    private fun rewriteArgIfMedia(args: List<Any?>) {
        val strIndex = args.indexOfFirst { it is String && BiliAcceleratorCore.hasMediaSignal(it) }
        if (strIndex == -1) return

        val originalUrl = args[strIndex] as String
        val config = RemoteClient.fetchConfig()
        if (!config.enabled) return

        val result = BiliAcceleratorCore.rewriteUrl(originalUrl, config)
        if (result.changed) {
            (args as? MutableList<Any?>)?.set(strIndex, result.finalUrl)
            RemoteClient.notifyRewrite(result)
            Log.i(TAG, "Rewrote media url: ${result.originalHost} -> ${result.targetHost} [${result.reason}]")
        }
    }

    private fun hookFallbackPlayer(module: XposedModule, classLoader: ClassLoader) {
        val ijkPlayerClass = try {
            classLoader.loadClass("tv.danmaku.ijk.media.player.IjkMediaPlayer")
        } catch (_: Throwable) {
            null
        } ?: return

        try {
            // 1. Hook setDataSource(String path)
            try {
                val setDataSourceMethod = ijkPlayerClass.getDeclaredMethod("setDataSource", String::class.java)
                module.hook(setDataSourceMethod).intercept { chain ->
                    val original = chain.args.getOrNull(0) as? String
                    if (original != null && BiliAcceleratorCore.hasMediaSignal(original)) {
                        val config = RemoteClient.fetchConfig()
                        if (config.enabled) {
                            val result = BiliAcceleratorCore.rewriteUrl(original, config)
                            if (result.changed) {
                                (chain.args as? MutableList<Any?>)?.set(0, result.finalUrl)
                                RemoteClient.notifyRewrite(result)
                                Log.i(TAG, "Rewrote IjkMediaPlayer dataSource: ${result.originalHost} -> ${result.targetHost}")
                            }
                        }
                    }
                    chain.proceed()
                }
            } catch (_: NoSuchMethodException) {}

            // 2. Hook setDataSource(Context context, Uri uri) 及重载
            val uriMethods = (ijkPlayerClass.declaredMethods + ijkPlayerClass.methods).distinct().filter {
                it.name == "setDataSource" && it.parameterTypes.size >= 2 && it.parameterTypes[1] == Uri::class.java
            }
            for (m in uriMethods) {
                try {
                    module.hook(m).intercept { chain ->
                        val originalUri = chain.args.getOrNull(1) as? Uri
                        if (originalUri != null) {
                            val original = originalUri.toString()
                            if (BiliAcceleratorCore.hasMediaSignal(original)) {
                                val config = RemoteClient.fetchConfig()
                                if (config.enabled) {
                                    val result = BiliAcceleratorCore.rewriteUrl(original, config)
                                    if (result.changed) {
                                        (chain.args as? MutableList<Any?>)?.set(1, Uri.parse(result.finalUrl))
                                        RemoteClient.notifyRewrite(result)
                                        Log.i(TAG, "Rewrote IjkMediaPlayer Uri dataSource: ${result.originalHost} -> ${result.targetHost}")
                                    }
                                }
                            }
                        }
                        chain.proceed()
                    }
                } catch (_: Throwable) {}
            }

            Log.i(TAG, "Successfully hooked IjkMediaPlayer setDataSource methods")
        } catch (t: Throwable) {
            Log.w(TAG, "hookFallbackPlayer failed: ${t.message}")
        }
    }
}
