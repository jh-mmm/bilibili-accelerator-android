# Bilibili Accelerator for Android (LSPosed / Xposed 模块)

本项目是将 [bilibili-accelerator](https://github.com/realzza/bilibili-accelerator) 移植到 Android 端的 Xposed/LSPosed 模块。
通过在应用内部 Hook 播放器媒体片段将慢速 PCDN、MCDN 节点无缝重定向至优质 UPOS 镜像，同时还有**实时拦截统计功能**。

---

## 核心特性

1. **PCDN 自动拦截与源站恢复**：
   - 识别住宅 P2P/PCDN 域名特征（`szbdyd.com`, `mountaintoys.cn`, `nexusedgeio.com`, `ahdohpiechei.com`, IP 形式以及非标准高位端口）。
   - 提取 `xy_usource` 参数恢复真实 CDN 源站；
   - 其它 PCDN 请求重定向至指定的极速 UPOS 镜像节点。
2. **MCDN 官方代理中继**：
   - 将 `*.mcdn.bilivideo.*` 节点包装转发至官方网关 `https://proxy-tf-all-ws.bilivideo.com/?url=...`，避免边缘节点卡顿与拥塞。
3. **gRPC 协议层抑制 (Moss TF 注入)**：
   - Hook `RuntimeHelper.tf` 注入 `TF=1` 标识，促使 B 站服务端直接下发官方镜像 CDN 节点。
4. **备用线路注入 (Failover Recovery)**：
   - Hook `setBackupUrls` 剔除慢速节点，补充多条同源 UPOS 镜像，当单节点遇阻时底层原生播放器无缝自动切流。
5. **完整保留统计功能**：
   - ⚡ 总重定向次数 (`totalRewrites`)
   - 🛡️ PCDN 拦截次数 (`pcdnBlocked`)
   - 🔄 MCDN 代理次数 (`mcdnProxied`)
   - 🌐 已规避的恶意/慢速节点数 (`avoidedHosts`)

---

## 编译与安装

1. **使用 Android Studio** 打开 `bilibili-accelerator-android` 项目。
2. 直接点击 **Build > Build Bundle(s) / APK(s) > Build APK(s)**。
3. 或在终端运行命令行编译：
   ```bash
   ./gradlew assembleDebug
   ```
4. 安装生成的 `app-debug.apk` 到手机上。

---

## LSPosed 激活步骤

1. 打开 **LSPosed** 管理器；
2. 在“模块”列表中找到 **Bili Accelerator** 并勾选启用；
3. 在模块作用域中勾选 **哔哩哔哩**（`tv.danmaku.bili` / `com.bilibili.app.in` / `com.bstar.intl` / HD版等）；
4. 强行停止并重新启动哔哩哔哩客户端；
5. 打开 Bili Accelerator 应用，顶部显示 **“已激活”** 即生效，播放任意视频即可在面板中观察到拦截与重定向计数递增。

---

## 开源协议

本项目基于 [MIT License](LICENSE) 许可协议开源。感谢上游项目 [realzza/bilibili-accelerator](https://github.com/realzza/bilibili-accelerator) 提供的协议分析与逻辑架构参考。

