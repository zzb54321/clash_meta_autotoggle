# clash_meta_autotoggle

根据当前连接的 WiFi 自动启用 / 停用 [ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid)（CMFA）或 [FlClash](https://github.com/chen08209/FlClash) 的安卓小工具，实现上以省电为第一目标。

当前版本：**v1.2.0**（APK 固定为 `apk/clash_meta_autotoggle.apk`，文件名不带版本号）

## 功能

- 支持三种客户端选项：**ClashMetaForAndroid（CMFA）**、**FlClash** 与 **自定义**（手动填写包名），可在首页切换（各自的包名独立保存）。
- 为每个 WiFi 名称（SSID）配置一条规则：连上该 WiFi 时**启用**或**停用**所选客户端。
- 未匹配的网络（其他 WiFi、移动数据、无网络）可选择：不改变状态 / 启用 / 停用。
- 一键添加当前连接的 WiFi，或手动输入 SSID。
- 包名说明：CMFA 为 `com.github.metacubex.clash.meta`，FlClash 为 `com.follow.clash`；其他分支（如 CMFA alpha、FlClash debug 版 `com.follow.clash.dev`）请选择「自定义」并手动填写包名，此时应用会自动探测该包支持的是 CMFA 还是 FlClash 风格的控制 Action。
- 开机、应用更新后自动恢复监听。

## 工作原理

两种客户端都提供了外部控制入口（一个透明的 Activity），本应用在网络变化时向其发送启动 / 停止意图。

ClashMetaForAndroid：`ExternalControlActivity`

- `com.github.metacubex.clash.meta.action.START_CLASH`
- `com.github.metacubex.clash.meta.action.STOP_CLASH`
- `com.github.metacubex.clash.meta.action.TOGGLE_CLASH`

FlClash：`TempActivity`

- `com.follow.clash.action.START`
- `com.follow.clash.action.STOP`
- `com.follow.clash.action.TOGGLE`

选择「自定义」时，应用会依次尝试 `<包名>.action.START_CLASH` 与 `<包名>.action.START`（停止同理），使用目标应用实际声明的那一个。

> FlClash 的外部控制要求其中已经保存过可用配置并授予过 VPN 权限，否则后台启动会静默失败（首次请手动启动一次 FlClash）。

意图带有 `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | FLAG_ACTIVITY_NO_USER_ACTION | FLAG_ACTIVITY_NO_ANIMATION`。其中 `MULTIPLE_TASK` 很关键：若不加，`NEW_TASK` 会复用客户端已存在的任务栈，从而把 FlClash / CMFA 的主界面带到前台；加上之后控制 Activity 会在独立任务中运行，切换全程无界面（CMFA 自身仍会弹出一条 Toast 提示，这是其自带行为，无法从外部关闭）。

> 还有一种会看到 FlClash 界面的情况来自 FlClash 自身：当它的主界面仍存活于后台且尚未授予 VPN 权限时，它会用那个 Activity 去 `startActivityForResult` 弹出系统 VPN 授权框，从而被系统带到前台。手动启动一次 FlClash 并完成 VPN 授权后即不再出现。

Android 10 起禁止应用在后台启动 Activity，被拦截时系统不会抛异常，只会静默丢弃。因此在发送意图前后，应用会借助「显示在其他应用上层」权限临时挂起一个 1x1 的透明悬浮窗（1 秒后移除），以满足系统的后台启动豁免条件；并在 6 秒后校验 VPN 通道状态，确认指令是否真的生效，未生效时会记入日志并清除“上次下发状态”，以便下次网络事件重试。

## 日志

首页底部提供「运行日志」区域，记录网络事件、判定过程、下发的 Action、目标组件以及生效校验结果，可刷新 / 复制 / 清空，最多保留 300 条并持久化保存（进程被杀后仍在）。同样的内容会输出到 logcat，可用 `adb logcat -s ClashAutoToggle` 查看。

## 省电设计

- **事件驱动，不轮询**：使用 `ConnectivityManager.registerNetworkCallback` 监听 WiFi 传输通道，系统只在 WiFi 连接 / 断开 / 能力变化时唤醒本进程，平时进程完全空闲。
- **不使用唤醒锁**，不使用周期性定时任务（`AlarmManager` / `JobScheduler`），不做后台扫描。
- **事件去抖**：网络切换时系统会连发多个回调，应用合并 2 秒内的事件，只判断一次。
- **状态去重**：仅在目标状态与当前状态不一致时才发送意图（结合“上次下发的状态”与系统 VPN 通道是否存在判断），避免反复唤醒 ClashMeta。
- 除 WiFi 传输通道外，还额外监听“默认网络”回调，避免部分 ROM 上 WiFi 断开事件延迟送达；两者共用同一套去抖与去重逻辑，不增加额外唤醒。
- **零依赖**：只使用 Android 框架 API，不引入任何第三方库，APK 约 40 KB。
- 关闭总开关时会停止前台服务，完全不占用后台资源。

## 权限说明

| 权限 | 用途 |
| --- | --- |
| 位置权限（精确 + 后台“始终允许”） | Android 10 起，读取当前 WiFi 的 SSID 必须拥有位置权限；后台权限用于锁屏 / 后台时判断 WiFi。应用只在网络变化时读取一次 SSID，不存储、不上传任何位置信息。 |
| 通知权限 | 前台服务的常驻通知（重要性最低，可折叠隐藏）。 |
| 显示在其他应用上层 | Android 10 起禁止后台启动 Activity，而 CMFA / FlClash 的外部控制入口都是 Activity，因此需要该权限才能在后台切换客户端。 |
| 忽略电池优化（可选） | 避免监听服务被系统冻结导致切换不及时。 |
| 开机自启 | 重启后恢复监听。 |

首次使用请点击首页的「授予所需权限」按钮，按提示逐项授权。

## 使用步骤

1. 安装 `apk/` 目录下的 APK，并确保已安装 ClashMeta（CMFA）或 FlClash 且已导入可用配置。
2. 打开本应用，点击「授予所需权限」，逐项完成授权，并在「Clash 客户端」处选择要控制的客户端（CMFA / FlClash / 自定义）。
3. 连接到目标 WiFi，点击「添加当前 WiFi」，选择该 WiFi 下是启用还是停用 ClashMeta。
4. 设置「未匹配的网络」的默认动作。
5. 打开顶部「启用 WiFi 自动切换」开关。

> 首次启动客户端时系统会弹出 VPN 授权对话框，需要手动确认一次（Android 的强制要求）。之后即可全自动切换。

## 编译

标准 Gradle / Android Studio 工程：

```bash
./gradlew assembleRelease   # 或在 Android Studio 中直接运行
```

仓库 `apk/` 目录中的 APK 使用标准 Android 调试签名（debug keystore）签名，方便直接安装；如需自己的签名请自行配置 `signingConfigs`。

> 由于调试签名的密钥在不同编译环境下会重新生成，重新编译发布的 APK 签名可能与旧版本不同。若安装时提示签名冲突，请先卸载旧版本再安装。

## 版本记录

- **v1.2.0**：新增应用内运行日志（可刷新 / 复制 / 清空，同时输出到 logcat，tag 为 `ClashAutoToggle`）；修复后台（尤其是断开 WiFi 时）指令被系统拦截导致不切换的问题；切换时不再把客户端界面带到前台；下发指令后会校验是否真正生效。
- **v1.1.0**：新增对 FlClash 的支持，客户端选项改为 CMFA / FlClash / 自定义三选一；修复标题栏遮挡界面的问题；发布的 APK 文件名不再包含版本号。
- **v1.0.0**：首个版本。WiFi 规则、默认动作、开机自启、省电的事件驱动监听、权限引导。
