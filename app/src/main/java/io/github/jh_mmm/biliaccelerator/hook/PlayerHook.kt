package io.github.jh_mmm.biliaccelerator.hook

import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object PlayerHook {

    private const val TAG = "BiliAccelerator-Player"

    // Both spellings are observed across different Bilibili versions (Assert vs Asset)
    private val SEGMENT_BUILDER_CLASSES = listOf(
        "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaAssertSegment\$Builder",
        "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaAssetSegment\$Builder",
        "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaSegment\$Builder"
    )

    fun init(classLoader: ClassLoader) {
        var hooked = false
        for (className in SEGMENT_BUILDER_CLASSES) {
            val builderClass = XposedHelpers.findClassIfExists(className, classLoader) ?: continue
            hookSegmentBuilder(builderClass)
            hooked = true
            XposedBridge.log("$TAG: Successfully hooked player segment builder: $className")
        }

        // Fallback: Hook IjkMediaPlayer directly if segment builder is missing or bypassed
        hookFallbackPlayer(classLoader)

        if (!hooked) {
            XposedBridge.log("$TAG: Standard IjkMediaAsset builder not found, relying on player fallback hooks")
        }
    }

    private fun hookSegmentBuilder(clazz: Class<*>) {
        // 1. Hook all constructors
        XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                rewriteArgIfMedia(param)
            }
        })

        // 2. Hook setter methods: setUrl, setBaseUrl, setResolveUrl
        val setterNames = setOf("setUrl", "setBaseUrl", "setResolveUrl")
        val methods = (clazz.declaredMethods + clazz.methods).distinct()
        for (m in methods) {
            if (m.name in setterNames) {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            rewriteArgIfMedia(param)
                        }
                    })
                } catch (_: Throwable) {}
            }
        }

        // 3. Hook setBackupUrls(Collection/List or Array)
        val backupMethods = (clazz.declaredMethods + clazz.methods).distinct().filter {
            it.name == "setBackupUrls" && it.parameterTypes.size == 1
        }

        for (method in backupMethods) {
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val arg = param.args.firstOrNull() ?: return
                        val rawList: List<String> = when (arg) {
                            is Collection<*> -> arg.filterIsInstance<String>()
                            is Array<*> -> arg.filterIsInstance<String>()
                            else -> return
                        }
                        if (rawList.isEmpty()) return

                        val config = RemoteClient.fetchConfig()
                        if (!config.enabled || !config.blockPcdn) return

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
                            param.args[0] = when {
                                paramType.isArray -> filtered.toTypedArray()
                                java.util.Set::class.java.isAssignableFrom(paramType) -> filtered.toSet()
                                else -> filtered
                            }
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: Failed to hook ${method.name}: ${t.message}")
            }
        }
    }

    private fun rewriteArgIfMedia(param: XC_MethodHook.MethodHookParam) {
        val strIndex = param.args.indexOfFirst { it is String && BiliAcceleratorCore.hasMediaSignal(it) }
        if (strIndex == -1) return

        val originalUrl = param.args[strIndex] as String
        val config = RemoteClient.fetchConfig()
        if (!config.enabled) return

        val result = BiliAcceleratorCore.rewriteUrl(originalUrl, config)
        if (result.changed) {
            param.args[strIndex] = result.finalUrl
            RemoteClient.notifyRewrite(result)
            XposedBridge.log("$TAG: Rewrote media url: ${result.originalHost} -> ${result.targetHost} [${result.reason}]")
        }
    }

    private fun hookFallbackPlayer(classLoader: ClassLoader) {
        val ijkPlayerClass = XposedHelpers.findClassIfExists("tv.danmaku.ijk.media.player.IjkMediaPlayer", classLoader) ?: return
        try {
            // 1. Hook setDataSource(String path)
            XposedHelpers.findAndHookMethod(ijkPlayerClass, "setDataSource", String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val original = param.args[0] as? String ?: return
                    if (!BiliAcceleratorCore.hasMediaSignal(original)) return
                    val config = RemoteClient.fetchConfig()
                    if (!config.enabled) return

                    val result = BiliAcceleratorCore.rewriteUrl(original, config)
                    if (result.changed) {
                        param.args[0] = result.finalUrl
                        RemoteClient.notifyRewrite(result)
                        XposedBridge.log("$TAG: Rewrote IjkMediaPlayer dataSource: ${result.originalHost} -> ${result.targetHost}")
                    }
                }
            })

            // 2. Hook setDataSource(Context context, Uri uri) 及重载
            val uriMethods = ijkPlayerClass.declaredMethods.filter {
                it.name == "setDataSource" && it.parameterTypes.size >= 2 && it.parameterTypes[1] == android.net.Uri::class.java
            }
            for (m in uriMethods) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val originalUri = param.args[1] as? android.net.Uri ?: return
                        val original = originalUri.toString()
                        if (!BiliAcceleratorCore.hasMediaSignal(original)) return
                        val config = RemoteClient.fetchConfig()
                        if (!config.enabled) return

                        val result = BiliAcceleratorCore.rewriteUrl(original, config)
                        if (result.changed) {
                            param.args[1] = android.net.Uri.parse(result.finalUrl)
                            RemoteClient.notifyRewrite(result)
                            XposedBridge.log("$TAG: Rewrote IjkMediaPlayer Uri dataSource: ${result.originalHost} -> ${result.targetHost}")
                        }
                    }
                })
            }

            XposedBridge.log("$TAG: Successfully hooked IjkMediaPlayer setDataSource methods")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: hookFallbackPlayer failed: ${t.message}")
        }
    }
}
