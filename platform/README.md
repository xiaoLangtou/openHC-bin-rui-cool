# platform —— 安卓适配层

协议核心（`:core:*`）只处理字节；这里负责把字节接到安卓系统上。按子包分：

| 子包 | 职责 |
|---|---|
| `usb` | 识别插入的华为手机、申请 USB 权限、发 carInfo 控制传输、手机切成 USB 网卡后抢占接口 |
| `net` | USB 网卡数据面：NTB16 组帧/拆帧、以太网/ARP/IPv4 最小处理、经 `VpnService` 建的 TUN 与系统 TCP 栈对接 |
| `media` | 视频 MediaCodec 解码上屏、触摸换算、屏幕刷新率；音频 AudioTrack 播放（`AudioPlayer`）和音频焦点（`AudioFocusController`）；车机麦克风采集（`MicCapture`、`MicAacEncoder`）；车机媒体键（媒体按钮会话 `MediaKeySession`、转发规则 `MediaKeyForwarder`） |
| `bluetooth` | 无线连接用的经典蓝牙：RFCOMM 服务端（`RfcommServer`）、读监听的实际通道号（`RfcommChannel`）、蓝牙开关状态；无线自动回连用的 BLE 广播（`BleBeacon`）和 GATT 服务端（`BeaconGattServer`）；补一次 LE 配对的入口（`LePairingPeripheral`） |
| `wifi` | 无线连接用的 WiFi：车机开本地热点（`LocalHotspotOwner`，规则在 `LocalHotspotRules`）、WiFi 现状（`WifiStatus`）、MAC 地址的整理与打码（`MacText`） |

能脱离安卓独立测试的纯逻辑（例如 NTB16、ARP 应答、校验和）写成不引用安卓类的普通类，
放在本模块 `src/test` 里用 JVM 单元测试覆盖。

## 有线连接怎么用

1. 先拿 VPN 授权：`VpnConsent.check(context)`，需要时用 Activity 拉起返回的 Intent（授权是持久的）。
   没授权就开始会话，会以 `VPN_CONSENT_REQUIRED` 结束，且不会发 carInfo。
2. 开会话：

   ```kotlin
   val session = WiredSession(context, WiredSessionConfig(carInfo, carAddress), object : WiredSession.Callback {
       override fun onStateChanged(state: WiredState) { /* 会话线程上调用，界面自己切主线程 */ }
       override fun onNetworkReady(network: WiredNetwork) {
           server = network.bindServerSocket(port) // 与 carInfo 里的端口一致；返回后才开始收发
       }
   })
   session.start()
   ```

   `carAddress` 必须与 carInfo 里写的车机地址一致。`onNetworkReady` 之后在自己的线程上 `server.accept()`，
   拿到的 Socket 就是手机的第一条 TCP 连接（控制通道）。

   carInfo 里的端口要在开会话前定好，监听有两种开法：
   - 在 `onNetworkReady` 里用 `bindServerSocket` 绑到车机地址（如上）：只有经 USB 网卡能连到；
     但万一 bind 失败，会话以 `ERROR` 结束，而这次插入的 carInfo 已经用掉，要拔插重来。
     所以端口最好在开会话前先试绑一次确认可用。
   - 开会话前先在通配地址上 bind 好监听，把它的端口写进 carInfo，`onNetworkReady` 里什么都不用做：
     不会 bind 失败，但车机别的网卡上也能连到这个端口，只能靠协议层的认证把关。
3. 结束：`session.stop()`。会话结束后不会自己重来。`Ended.replugRequired` 为 true 时要提示用户拔线、
   等 5 秒以上再插，**不要马上自动开新会话**：会话结束后约 1.5 秒手机会自己退回普通状态，
   这时再发 carInfo 手机不理（实测），新会话只会白弹一次授权框、再空等到超时。

VpnService 已在本模块清单里声明，合并进应用后自动生效；TUN 只路由车机所在网段、只对本应用生效，不影响车机自己上网。

## 无线连接用到的蓝牙和 WiFi

本模块只做安卓接口的适配，类型都是自己的（不依赖 core:session；GATT 服务端按 core:nearby 的布局和回应规则挂服务，
只在内部用）；把它们接到无线会话的平台动作上，在应用里（`app` 的 `wireless` 包）。

**RFCOMM 服务端**（`RfcommServer`，实现 `RfcommEndpoint`）：

```kotlin
val server = RfcommServer(adapter, "OpenHC", uuid, object : RfcommEvents { /* 在监听自己的线程上按顺序回调 */ })
server.start()            // 开监听（不加密、带服务记录）→ onListening(通道号) → accept → onAccepted(对端地址) → onData…
server.write(bytes)       // 不阻塞，写线程按顺序发
server.closeListener()    // 撤掉服务记录、不再接受；已接受的连接不受影响
server.closeConnection()  // 先发完排着的写（最多 1.5 秒），再关
```

- 只接受一条连接。通道号用反射读（见 `RfcommChannel`），读不到交 0。
- 安卓 12 起要「附近的设备」权限（BLUETOOTH_CONNECT），由应用在开始前申请；没有时报 `onListenFailed`。
- 对端关闭时安卓的蓝牙 socket 多半报读出错而不是读到结尾，这里按错误信息认出来报成 `onClosed`。

**本地热点**（`LocalHotspotOwner`，无线连接时车机给手机准备的 WiFi 网络，安卓 8.0 起）：

```kotlin
val hotspots = LocalHotspotOwner.get(context)                  // 进程里只有一个：系统按进程记热点申请
val handle = hotspots.create(events) { log(it) }
handle.start()   // （必要时等间隔）→ 申请 → 系统开好 → 读名称、密码、加密方式 → 等热点网卡上有 IPv4 → events.onReady(LocalHotspot)
handle.stop()    // 稍等 0.2 秒（让会话最后几个包发出去）就关热点，不等手机离开
```

- 名称、密码由系统生成，开好后读回来；手机按普通 WiFi 加入。车机地址：开热点前后各拍一次网卡快照，取新出现的 IPv4
  （单射频车机开热点时自己的 WiFi 会断，热点地址常出现在同一块网卡上），名字像热点的网卡优先（`LocalHotspotRules.pickInterface`）。
- **频率多半是推测值**：普通应用读不到本地热点实际开在哪个信道（只有系统接口给得出）。系统配置里写明了信道就用它；
  只知道频段时 2.4 GHz 报 2437、5 GHz 报 5745；都不知道按 2437（本地热点默认在 2.4 GHz）。`HotspotFrequencySource` 标明来源。
- BSSID：系统配置里给了就用，其次热点网卡的硬件地址（安卓 10 及以前读得到），都没有用占位值 `02:00:00:00:00:00`（`HotspotMacSource` 标明）。
- 申请的规则（`HotspotRequestMachine`，JVM 单元测试覆盖）：两次申请之间至少隔 3.5 秒（刚关就申请，系统会一直不回话、半分钟后才报失败）；
  主人中途走了而申请还没回话，留着，回话说开好了就立刻关掉，期间来了新连接直接交给它；热点被关、被系统收回都不依赖「手机已离开」的通知；
  申请 10 秒没回话按失败报。
- 每次申请前记下车机 WiFi 开没开（`stationWasOnBeforeHotspot`，粘住「开过」，用的人处理完调 `clearStationNote`），
  给「退出后恢复车机 WiFi」用；`busy` 表示有连接在用或在等热点。
- 系统要求：安卓 8～12 要精确位置权限、系统定位开关打开；安卓 13 起要「附近的 WiFi 设备」（清单里声明 `neverForLocation` 也行）；
  应用在前台（有前台服务也算）。车机设置里的个人热点开着时开不了。权限由调用方在开始前确认；没有时报 `onFailed`，说明里带原因。
- 开好以后被系统收回（WiFi 被关、别处开了热点）报 `onLost`。

**BLE 广播**（`BleBeacon`，无线自动回连用；载荷由 core:nearby 的 `BleAdvertisement` 拼）：

```kotlin
when (BleBeacon.support(adapter)) { /* Available / Unavailable(原因)：没蓝牙、蓝牙关着、系统报告不支持多广播 */ }
val beacon = BleBeacon(adapter, BleAdvertisement.SERVICE_DATA_UUID, payload, events) // 一次广播一个对象
beacon.start()   // → events.onStarted() / onFailed(原因, permanent)，在主线程上
beacon.stop()    // 任意时刻可调、可重复；还在启动中就等系统回了结果再停
```

- 只用老接口 `startAdvertising`：低延迟（间隔 100 ms）、可连接（因而可扫描）、中档功率、不超时，只带一段 Service Data，
  不带设备名和功率字段；新系统内部换算成同样的广播集参数，安卓 6.0 起一条路径。换载荷（换序列号）就停掉、开新的。
- **发送地址由系统蓝牙栈决定**（开了 LE 隐私就是随机地址），应用改不了也读不到；手机按广播的发送地址去连车机经典蓝牙，
  所以只有发送地址就是车机蓝牙地址的车机上，配对过的手机才能靠广播回连。要在真机上用别的设备扫描核对。
- 权限：安卓 12 起「附近的设备」里的广播权限（BLUETOOTH_ADVERTISE），更早用清单里的普通蓝牙权限。

**GATT 服务端**（`BeaconGattServer`，和广播一起挂）：

```kotlin
val gatt = BeaconGattServer(context, events, log)
gatt.open()    // 开服务端，按布局串行加两个主服务（前一个的添加回调到了再加下一个）→ events.onReady()
gatt.close()   // 撤掉服务、断开客户端；可重复调用
```

- 只照布局挂好、按 core:nearby 的 `NearbyGattResponder` 规则回应，不在 GATT 上跑控制通道；每次连接、MTU、读写、
  CCCD 都经 `log` 记下来（特征、长度、是否像控制帧，不记内容），真机上据此看有没有手机来用。
- 有客户端连上时报 `onClientConnected`：部分车机在可连接广播被连上后就停发，调用方据此把广播关了再开一次。
- 权限：安卓 12 起「附近的设备」（BLUETOOTH_CONNECT）。

**补一次 LE 配对的入口**（`LePairingPeripheral`，设置「补全手机蓝牙配对」用）：

```kotlin
val entrance = LePairingPeripheral(context, adapter, events, log)
entrance.open()    // 加服务 → 服务加好后开广播 → events.onAdvertising()；失败报 onFailed(Failure)
entrance.close()   // 停广播、撤掉服务；可重复调用
```

- 车机开了 LE 隐私时，只做过经典蓝牙配对的手机解不回广播的随机地址；手机在自己的蓝牙设置里和这个入口配对一次，
  就拿到了车机的身份解析密钥。本类只开入口，配对由两边的系统蓝牙完成，结果看系统的配对状态广播。
- GATT：电池服务 `180F` 下一个电池电量特征 `2A19`，只读、读要加密链路；读请求能到应用，说明链路已经加密（`onEncryptedRead`）。
- 广播：传统广播、可连接、低延迟、高功率，带完整设备名和服务 UUID `180F`，所以设备名最多 22 字节。设备名就是系统当前的
  蓝牙名称，由调用方事先改好（应用里临时改成一个好认的名称，结束时改回）。
- 权限：安卓 12 起「附近的设备」（BLUETOOTH_CONNECT、BLUETOOTH_ADVERTISE）。

## 解码上屏和触摸怎么用

协议层（`HiSightReceiver`）交出的 H.264 帧由 `media.VideoDecoder` 硬解上屏；触摸由 `media.TouchMapper`
换算成 `HiSightInput.Touch`。应用里的投屏视图（`app` 的 `ProjectionView`，Compose 用 `ProjectionSurface`）已经把
Surface、触摸、全屏和常亮接好。

```kotlin
val decoder = VideoDecoder(object : VideoDecoder.Listener {
    // 解码线程上回调
    override fun onVideoSizeChanged(width: Int, height: Int) = projectionView.setVideoSize(width, height)
    override fun onKeyFrameNeeded(reason: VideoDecoder.ResyncReason) { /* 协议支持时向手机请求关键帧 */ }
})
val receiver = HiSightReceiver(sessionKey, config, random, videoSink = decoder) // 每帧在 feedVideo 的线程上同步送进解码器
// 收到 HiSightOutput.StreamNegotiated：
decoder.prepare(out.width, out.height, out.fps)
projectionView.attachDecoder(decoder) // 有 Surface 时同步交给解码器，顺带交屏幕的刷新时序（上屏节拍用）
projectionView.onInput = { input ->
    // receiver.sendInput(input) 返回的包写到回控连接。抛 IllegalStateException（触摸状态对不上）时
    // 调 projectionView.resetTouches()：只清本地状态、不补发抬起，下一次按下重新开始。
}
// 会话结束：decoder.close()
```

- 回控连接就绪（`onRemoteControlConnected` 之后）再把触摸接到 `sendInput`：就绪前的输入会被静默丢弃，
  之后两边的按下状态对不上；回控连接关掉时调一次 `projectionView.resetTouches()`。
- `StreamNegotiated.touchInput` 为 false 时手机不收触摸，`sendInput` 会抛 `IllegalArgumentException`，不要接。

- 向手机声明的帧率：`DisplayRefreshRate.declaredFps(context)`，按屏幕刷新率四舍五入、最高 60，填进 `HiSightConfig.fps`。
- 建议同时声明关键帧间隔（GOP）：协议里没有「请马上发关键帧」的消息，丢帧或换解码器后要等下一个关键帧。
  应用按约 2 秒声明（帧率 × 2，限制在协议允许的 30～600 帧）。
- 送帧不会被解码器卡住：一时没有空闲输入缓冲的帧留在解码器的缓存里，拿到缓冲后按顺序补送，不丢。
  输出换到新的视图 Surface 后（切后台再回来、视图重建），解码器自己重放缓存、画出最后一帧，手机画面不变、
  不发新帧时也不会黑屏；接 Surface 的方式不用变。
- **上屏节拍**（默认开，`VideoDecoder.Options.framePacing`，运行中可以改 `decoder.framePacing`）：系统合成器每次刷新
  只取一帧，同一次刷新之前到了两帧，前一帧就被挤掉、不显示。帧率接近刷新率、到达时刻有抖动时这很常见
  （平板真机 60 帧时「上屏」只有 44～49，30 帧时几乎不少）。开了以后多数帧照样马上送，和上一帧挤进同一次刷新的
  顺延到下一次刷新；每帧最多晚一个刷新周期（60Hz 屏 16.7ms，55Hz 屏 18.2ms），平均多等多少取决于抖动的样子，
  统计里直接给出。扣帧的时机按 Choreographer 的刷新回调和屏幕给的时序算：`ProjectionView` 拿到 Surface 时自动
  `setDisplayTiming`，自己接 Surface 的要自己调 `decoder.setDisplayTiming(DisplayRefreshRate.timingOf(display))`。
  应用设置页「帧率」下面有开关（下次连接生效）；调试演示页能当场切换，还能加「送帧抖动」，不连手机也能对照。
- **统计日志**（tag `OpenHC-Video`，每 5 秒打一组，内容是最近 1 秒）：
  - 第一行：输入、解码、上屏帧率，丢帧，解码延迟。「上屏」来自系统的上屏回调：按开源框架的实现看，OMX 解码器
    只为真正显示过的帧回调，被合成器挤掉的帧没有；有的解码器在送去显示时就回调，这时它反映不出挤掉的帧。
  - 第二行：「两帧同刷新」是推算会和上一帧挤进同一次刷新的帧数，节拍关时它应当约等于「解码 − 上屏」；
    「顺延」「顶替」是节拍做的事（顶替 = 要晚两拍才轮得到，改为丢掉排着的那一帧）；「出帧到合成」是出帧到被合成器
    取走的推算时间，两种做法之差就是节拍多出来的延迟，也直接给在「节拍多等」；「到达抖动」是两帧到达间隔与手机时间戳
    间隔之差（传输和接收这一路加的抖动），「手机间隔」是手机时间戳间隔的最小～最大值（手机出帧本身匀不匀）。
- 线程模型、缓存与重放、丢帧与恢复、低延迟的做法写在 `VideoDecoder` 的类注释里；多指的处理规则写在 `TouchMapper` 的类注释里。
- 调试构建多一个「OpenHC 投屏演示」页（不放桌面图标，用 `adb shell am start -n io.github.gcbond.openhc/.demo.ProjectionDemoActivity` 打开）：不连手机，播放运行时现生成的合成码流，用来在车机上检查解码、
  显示比例、全屏、切后台恢复和触摸换算（十字准星应当正好在手指下面）。点「暂停出帧」后切后台再回来，
  画面应当马上回到暂停时的那一帧（重放缓存）。

## 声音怎么放

`media.AudioPlayer` 播放手机投过来的一路声音，一次数据会话里的一路一个对象：

```kotlin
val player = AudioPlayer(AudioPlayer.Format(48_000, 2, AudioPlayer.Encoding.PCM_16), { event -> log(event) }, AudioTuning(), AudioUsage.NAVIGATION)
player.queuePcm(data, offset, length, bigEndian = false)   // 任意线程，复制后入队，不阻塞
// AAC：Encoding.AAC + queueAac（每次一个 AAC-LC 帧，可以带 ADTS 头；没有 ADTS 头时按 48kHz 双声道配置解码器）
player.setVolume(0.3f)                                    // 失去焦点时压低或静音，任意线程
log(player.latency)                                       // 我们这边的延迟拆分，播放线程约每 250ms 更新
player.close()                                            // 数据会话结束时；不阻塞
```

- 解码和阻塞的 `AudioTrack.write` 在播放器自己的线程上。音轨用途按 `AudioUsage`：媒体 `USAGE_MEDIA`、导航
  `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`、提示与语音 `USAGE_ASSISTANT`（安卓 8.0 以下 `USAGE_ASSISTANCE_SONIFICATION`）、
  通话 `USAGE_VOICE_COMMUNICATION` + `CONTENT_TYPE_SPEECH`（车机的回声消除拿这一路当参考）、铃声 `USAGE_NOTIFICATION_RINGTONE`，
  车机按它选音量档。播放器本身不申请焦点。
  安卓 8.0 起默认向系统要低延迟的输出路径（`AudioTuning.lowLatency`）。
- 第一次出声报 `Event.Started`，放空后重新出声报 `Event.Resumed`，放空报 `Event.Starved`：焦点按这几个事件算一路还在不在响。
- 缓冲参数在 `AudioTuning`，默认按有线投屏取低值：攒 80ms 起播，AudioTrack 缓冲约 40ms（不小于系统最小值）；
  起播（含放空后重新起播）后 1 秒内队列高于 100ms 就直接压回 100ms（手机开播时常一口气补发一批）；之后队列在每次
  取走后仍高于 100ms 且持续 2 秒，就从最旧的丢起，每次最多约 20ms、两次至少隔 200ms；突发积压超过 300ms 一次丢回 100ms，
  输出放空（欠载，包括手机暂停播放）时暂停、重新攒够再放。`extraDelayMillis`（「声音延后」）加在这三档门槛上。
- 延迟拆分在 `AudioLatency`：队列、解码、音轨已写未播（写入帧数 − 播放头）、硬件（播放头 − 由 `getTimestamp`
  推算的此刻正在输出的帧）；拿不到时间戳时硬件部分记为未知，不估。
- 建 AudioTrack 后报一次 `Event.TrackReady`：系统最小缓冲、申请的和实际拿到的缓冲、容量、实际的性能模式。
- 排队策略在纯逻辑的 `AudioJitterQueue`；`AacConfig` 生成解码器的 `csd-0`、解析 ADTS 头。纯逻辑部分都有 JVM 单元测试。

### 音频焦点

`media.AudioFocusController` 按用途各自向系统申请焦点：手机要焦点时照它要的类型（长期、短暂、短暂并压低别人、短暂独占），
按出声申请时媒体长期、导航和提示与语音短暂并压低别人、通话短暂；已经拿着时只升级不降级。
决策在纯逻辑的 `AudioFocusPolicy`（规则写在类注释里，有 JVM 单元测试）：

```kotlin
val focus = AudioFocusController(context, object : AudioFocusController.Listener {
    override fun onVolume(usage: AudioUsage, volume: Float) { /* 这个用途的播放器 setVolume */ }
    override fun onPhoneChange(change: AudioFocusPolicy.PhoneChange) { /* 告诉手机暂停、恢复或压低 */ }
    override fun onLog(message: String) { log(message) }
})
focus.phoneRequested(AudioUsage.NAVIGATION, AudioFocusPolicy.Gain.TRANSIENT_MAY_DUCK)   // 手机要焦点；phoneAbandoned 放弃
focus.soundStarted(AudioUsage.NAVIGATION)     // 播放器开始 / 重新出声；soundStopped 放空或关掉
focus.close()                                 // 连接结束：放掉全部焦点
```

- 车机别的应用长期拿走焦点：媒体静音、告诉手机停下；暂时拿走：静音、告诉手机暂停，拿回来恢复并告诉手机接着放；
  要短暂出声（可压低）：告诉手机可以压低，安卓 8.0 起系统自己会压低我们，更低的版本我们自己压低。
- 每个用途在系统里是各自的焦点客户端：我们申请一个用途时，正拿着焦点的另几个用途会各收到一次变化（长期申请 → 长期失去，
  短暂 → 暂时失去，压低别人 → 可压低）。这些按申请时记下的次数不理会、不告诉手机，例如来电后铃声出声不会把通话静音，
  播报中开始放歌不会掐掉播报。
- 手机报来电、通话中（`callStateChanged`）：申请通话焦点并一直拿着，媒体在本地静音，挂断后恢复；报语音助手在用
  （`assistantStateChanged`）：媒体在本地压低，空闲或 30 秒没消息后恢复。都不告诉手机。
- 数据会话被手机拆掉（`dataSessionEnded`）放掉焦点但保留通话中的通话焦点；`close` 全部放掉。
- 线程：方法任意线程调用，决策和安卓调用都在自己的线程上；安卓 8.0 起用 `AudioFocusRequest`，更低的版本用老接口。

## 车机麦克风怎么采

`media.MicCapture` 采车机麦克风，每 20ms 交出一块 16 位小端交错 PCM，格式照手机这次协商的（采样率、声道）：

```kotlin
val capture = MicCapture(MicCapture.Format(16_000, 1), { data, length -> send(data.copyOf(length)) }) { event -> log(event) }
if (!capture.start()) { /* 开不了：原因已经经回调报出 */ }   // 调用线程上开 AudioRecord，之后在自己的线程上读
// 手机要 AAC 时：val encoder = MicAacEncoder(48_000, 2) { adtsFrame -> send(adtsFrame) }，Sink 里调 encoder.encode(data, length)
capture.close()                                                 // 停止、释放，等采集线程退出
```

- 音源优先 `VOICE_COMMUNICATION`，开不了退到 `MIC`；系统有回声消除（`AcousticEchoCanceler`）、降噪（`NoiseSuppressor`）就打开，
  开好后报一次 `Event.Opened`（实际音源、采样率、两个音效有没有开成）。
- 一律采单声道。设备开不了要的采样率时依次试 48k、16k、44.1k、32k、8k，用开得了的采，再经纯逻辑的 `PcmResampler`
  （线性插值、跨块保持相位）换成要的；要双声道时复制成两个声道。
- 要录音权限，由调用方先检查；没有时 `start` 返回 false。
- `MicAacEncoder` 用 MediaCodec 编 AAC-LC（16kHz 及以下每声道 32kbps，更高的 64kbps），每帧加 7 字节 ADTS 头
  （纯逻辑的 `AdtsWriter`）。在调用 `encode` 的线程上同步送入、取出。
