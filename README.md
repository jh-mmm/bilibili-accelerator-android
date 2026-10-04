# Bilibili Accelerator for Android

**一个致力于改善哔哩哔哩客户端视频加载体验的 LSPosed 模块。**

本项目是 [realzza/bilibili-accelerator](https://github.com/realzza/bilibili-accelerator) 的非官方 Android 移植版。通过在 B 站客户端内部 Hook 播放器媒体片段请求，将慢速的 PCDN、MCDN 节点无缝重定向至优质的官方 UPOS 镜像，从而告别视频缓冲卡顿，并配有直观的**实时拦截统计看板**。

---

## 核心特性

本模块在底层对视频流请求进行拦截与重定向，保障播放体验。

* 拦截住宅 P2P/PCDN 节点（包括 `szbdyd.com`, `mountaintoys.cn`, `nexusedgeio.com`, `ahdohpiechei.com`，以及非常规的高位端口和裸 IP 节点）。自动提取 `xy_usource` 参数，强制恢复至真实的 CDN 源站。
    *   对于其他劣质 PCDN 请求，直接重定向至极速 UPOS 镜像节点。
* 配有数据可视化的实时统计面板**

---

## 安装与激活

### 前置要求
*   已 Root 的 Android 设备
*   已安装并激活 [LSPosed](https://github.com/LSPosed/LSPosed) 框架
*   哔哩哔哩官方客户端（支持标准版 / 概念版 / 国际版 / HD 版等）

### 激活步骤
1. 下载最新版的 `app-release.apk` 并安装。
2. 打开 **LSPosed Manager**，在“模块”列表中找到 **Bili Accelerator** 并勾选启用。
3. 在模块的作用域设置中，勾选你需要加速的 B 站客户端（如：`tv.danmaku.bili` / `com.bilibili.app.in` / `com.bstar.intl` 等）。
4. **强行停止**（杀后台）哔哩哔哩客户端。
5. 重新打开哔哩哔哩。
6. 打开本模块的 APP，若顶部状态显示 **“✅ 已激活”**，即代表 Hook 成功。此时去 B 站随意播放一个视频，即可在面板中观察到拦截与重定向计数递增！

---

## 遇到问题？

**Q1: 打开模块 APP 显示“未激活”怎么办？**
*   请检查 LSPosed 管理器中是否已勾选启用本模块，并且**正确勾选了哔哩哔哩客户端作为作用域**。
*   每次更新模块或修改 LSPosed 作用域后，必须**强行停止**（杀掉后台）哔哩哔哩客户端后重新打开才会生效。
*   如果确认操作无误但依然显示未激活，请尝试重启手机或重启 LSPosed 服务。

**Q2: 播放视频时一直转圈缓冲，或者提示加载失败？**
*   可能是 B 站服务端临时调整了节点策略，或者你的网络运营商对重定向后的 UPOS 镜像节点连通性不佳。
*   部分极旧或最新的 B 站客户端版本可能更改了底层播放逻辑。尝试在 LSPosed 中暂时关闭本模块排查是否为网络原生问题。如果确认是模块导致，请在 Issue 中反馈你使用的 B 站版本号。

**Q3: 播放了视频，但面板里的拦截/重定向统计数据一直为 0？**
*   请先确认模块状态是否为“已激活”。
*   在非高峰时段或播放冷门视频时，B 站可能本身就直接下发了官方优质节点，没有触发 PCDN/MCDN 规则，此时计数不增加属于正常现象。
*   如果持续多天、播放任何视频都不增加，可能是客户端版本更新导致 Hook 点（如 `RuntimeHelper.tf` 或 `setBackupUrls`）失效，请关注模块的后续更新。

---

## 反馈与问题提交

如果你在使用本模块时遇到了 Bug、闪退、或者有功能建议，请直接在**本仓库的 Issues** 页面提交，我会尽快处理。

---

## 编译指南

如果你想自行编译此项目：

1. 使用 **Android Studio** 打开 `bilibili-accelerator-android` 项目。
2. 等待 Gradle 同步完成后，点击菜单栏：**Build > Build Bundle(s) / APK(s) > Build APK(s)**。
3. 或者在终端运行以下命令：
   ```bash
   ./gradlew assembleDebug

```

4. 编译成功后，在 `app/build/outputs/apk/debug/` 目录下获取 `app-debug.apk` 并安装。

---

## 鸣谢与开源协议

本项目基于 [MIT License](https://www.google.com/search?q=LICENSE) 许可协议开源。

特别感谢上游项目 [realzza/bilibili-accelerator](https://github.com/realzza/bilibili-accelerator) 的原作者。本 Android 模块的核心逻辑架构与协议分析均参考自该项目！

---


