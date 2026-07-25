# clash_meta_autotoggle

根据当前连接的 WiFi 自动启用 / 停用 [ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid)（CMFA）的安卓小工具，实现上以省电为第一目标。

当前版本：**v1.0.0**（`apk/clash_meta_autotoggle-v1.0.0.apk`）

## 功能

- 为每个 WiFi 名称（SSID）配置一条规则：连上该 WiFi 时**启用**或**停用** ClashMeta。
- 未匹配的网络（其他 WiFi、移动数据、无网络）可选择：不改变状态 / 启用 / 停用。
- 一键添加当前连接的 WiFi，或手动输入 SSID。
- 支持自定义 ClashMeta 包名（默认 `com.github.metacubex.clash.meta`，可改为 alpha 版等）。
- 开机、应用更新后自动恢复监听。

## 工作原理

ClashMetaForAndroid 提供了外部控制入口 `ExternalControlActivity`，接受以下 Action：

- `com.github.metacubex.clash.meta.action.START_CLASH`
- `com.github.metacubex.clash.meta.action.STOP_CLASH`
- `com.github.metacubex.clash.meta.action.TOGGLE_CLASH`

本应用在网络变化时向该 Activity 发送 START / STOP 意图，从而启动或停止代理。

## 省电设计

- **事件驱动，不轮询**：使用 `ConnectivityManager.registerNetworkCallback` 监听 WiFi 传输通道，系统只在 WiFi 连接 / 断开 / 能力变化时唤醒本进程，平时进程完全空闲。
- **不使用唤醒锁**，不使用周期性定时任务（`AlarmManager` / `JobScheduler`），不做后台扫描。
- **事件去抖**：网络切换时系统会连发多个回调，应用合并 2 秒内的事件，只判断一次。
- **状态去重**：仅在目标状态与当前状态不一致时才发送意图（结合“上次下发的状态”与系统 VPN 通道是否存在判断），避免反复唤醒 ClashMeta。
- **零依赖**：只使用 Android 框架 API，不引入任何第三方库，APK 约 40 KB。
- 关闭总开关时会停止前台服务，完全不占用后台资源。

## 权限说明

| 权限 | 用途 |
| --- | --- |
| 位置权限（精确 + 后台“始终允许”） | Android 10 起，读取当前 WiFi 的 SSID 必须拥有位置权限；后台权限用于锁屏 / 后台时判断 WiFi。应用只在网络变化时读取一次 SSID，不存储、不上传任何位置信息。 |
| 通知权限 | 前台服务的常驻通知（重要性最低，可折叠隐藏）。 |
| 显示在其他应用上层 | Android 10 起禁止后台启动 Activity，而 CMFA 的外部控制入口是 Activity，因此需要该权限才能在后台切换 ClashMeta。 |
| 忽略电池优化（可选） | 避免监听服务被系统冻结导致切换不及时。 |
| 开机自启 | 重启后恢复监听。 |

首次使用请点击首页的「授予所需权限」按钮，按提示逐项授权。

## 使用步骤

1. 安装 `apk/` 目录下的 APK，并确保已安装 ClashMeta（CMFA）且已导入可用配置。
2. 打开本应用，点击「授予所需权限」，逐项完成授权。
3. 连接到目标 WiFi，点击「添加当前 WiFi」，选择该 WiFi 下是启用还是停用 ClashMeta。
4. 设置「未匹配的网络」的默认动作。
5. 打开顶部「启用 WiFi 自动切换」开关。

> 首次启动 ClashMeta 时系统会弹出 VPN 授权对话框，需要手动确认一次（Android 的强制要求）。之后即可全自动切换。

## 编译

标准 Gradle / Android Studio 工程：

```bash
./gradlew assembleRelease   # 或在 Android Studio 中直接运行
```

仓库 `apk/` 目录中的 APK 使用标准 Android 调试签名（debug keystore）签名，方便直接安装；如需自己的签名请自行配置 `signingConfigs`。

## 版本记录

- **v1.0.0**：首个版本。WiFi 规则、默认动作、开机自启、省电的事件驱动监听、权限引导。
