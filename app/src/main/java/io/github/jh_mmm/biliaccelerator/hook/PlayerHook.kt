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
            HookStatusTracker.recordStatus("player_segment", className)
            Log.i(TAG, "Successfully hooked player segment builder: $className")
        }

        // Fallback: Hook IjkMediaPlayer directly if segment builder is missing or bypassed
        hookFallbackPlayer(module, classLoader)

        if (!hooked) {
            Log.i(TAG, "Standard IjkMediaAsset builder not found, relying on player fallback hooks")
        }
    }

    private fun hookSegmentBuilder(module: XposedModule, clazz: Class<*>) {
        // 1. Hook all constructors (distinct 防止 public 构造器重复注入)
        val constructors = (clazz.declaredConstructors + clazz.constructors).distinct()
        for (constructor in constructors) {
            try {
                module.hook(constructor).intercept { chain ->
                    val newArgs = rewriteArgIfMedia(chain.args)
                    if (newArgs != null) {
                        chain.proceed(newArgs)
                    } else {
                        chain.proceed()
                    }
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
                        val newArgs = rewriteArgIfMedia(chain.args)
                        if (newArgs != null) {
                            chain.proceed(newArgs)
                        } else {
                            chain.proceed()
                        }
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
                            // 仅保留确认不是 PCDN 的干净官方线路
                            filtered.add(url)
                        }
                    }

                    // 备用镜像池维护：若原始地址全部是 PCDN 导致过滤后无地址或只有单一边界节点，
                    // 从 CANDIDATE_POOL 挑选不同云服务商（阿里/华为/腾讯等）生成备选直连镜像，确保播放器具备真实的高可用容灾能力
                    val sampleUrl = rawList.firstOrNull()
                    if (filtered.isEmpty() && sampleUrl != null) {
                        val candidates = BiliAcceleratorCore.CANDIDATE_POOL.filter { it != target }
                        for (candidate in candidates.take(3)) {
                            val backupRes = BiliAcceleratorCore.rewriteUrl(
                                sampleUrl,
                                config.copy(targetHost = candidate, forceUpos = true)
                            )
                            if (backupRes.changed) {
                                filtered.add(backupRes.finalUrl)
                            }
                        }
                    }

                    val distinctFiltered = filtered.distinct()
                    if (distinctFiltered.isNotEmpty()) {
                        val paramType = method.parameterTypes[0]
                        val convertedArg: Any = when {
                            paramType.isArray -> distinctFiltered.toTypedArray()
                            java.util.Set::class.java.isAssignableFrom(paramType) -> distinctFiltered.toSet()
                            else -> distinctFiltered
                        }
                        val newArgs = chain.args.toTypedArray().apply { this[0] = convertedArg }
                        return@intercept chain.proceed(newArgs)
                    }
                    chain.proceed()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to hook ${method.name}: ${t.message}")
            }
        }
    }

    private fun rewriteArgIfMedia(args: List<Any?>): Array<Any?>? {
        val strIndex = args.indexOfFirst { it is String && BiliAcceleratorCore.hasMediaSignal(it) }
        if (strIndex == -1) return null

        val originalUrl = args[strIndex] as String
        val config = RemoteClient.fetchConfig()
        if (!config.enabled) return null

        val result = BiliAcceleratorCore.rewriteUrl(originalUrl, config)
        if (result.changed) {
            RemoteClient.notifyRewrite(result)
            Log.i(TAG, "Rewrote media url: ${result.originalHost} -> ${result.targetHost} [${result.reason}]")
            val newArgs = args.toTypedArray()
            newArgs[strIndex] = result.finalUrl
            return newArgs
        }
        return null
    }

    private fun hookFallbackPlayer(module: XposedModule, classLoader: ClassLoader) {
        val ijkPlayerClass = try {
            classLoader.loadClass("tv.danmaku.ijk.media.player.IjkMediaPlayer")
        } catch (_: Throwable) {
            null
        } ?: return

        var fallbackHooked = false

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
                                RemoteClient.notifyRewrite(result)
                                Log.i(TAG, "Rewrote IjkMediaPlayer dataSource: ${result.originalHost} -> ${result.targetHost}")
                                val newArgs = chain.args.toTypedArray().apply { this[0] = result.finalUrl }
                                return@intercept chain.proceed(newArgs)
                            }
                        }
                    }
                    chain.proceed()
                }
                fallbackHooked = true
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
                                        RemoteClient.notifyRewrite(result)
                                        Log.i(TAG, "Rewrote IjkMediaPlayer Uri dataSource: ${result.originalHost} -> ${result.targetHost}")
                                        val newArgs = chain.args.toTypedArray().apply { this[1] = Uri.parse(result.finalUrl) }
                                        return@intercept chain.proceed(newArgs)
                                    }
                                }
                            }
                        }
                        chain.proceed()
                    }
                    fallbackHooked = true
                } catch (_: Throwable) {}
            }

            if (fallbackHooked) {
                HookStatusTracker.recordStatus("player_fallback", "tv.danmaku.ijk.media.player.IjkMediaPlayer")
                Log.i(TAG, "Successfully hooked IjkMediaPlayer setDataSource methods")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hookFallbackPlayer failed: ${t.message}")
        }
    }
}
