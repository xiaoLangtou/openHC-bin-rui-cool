# 架构说明

## 一次有线连接的数据流

```
华为手机 ══ USB 数据线 ══ 车机（OpenHC）

 ① platform.usb      识别手机 → 申请 USB 权限 → 发 carInfo（字节由 core.nearby 生成）
                      → 手机切换成 USB 网卡
 ② platform.net      抢占网卡接口：NTB16 组帧/拆帧、应答 ARP，
                      IP 包经 VpnService 建的 TUN 交给系统 TCP 栈
 ③ core.nearby       控制通道（第一条 TCP 连接）上的帧收发与加解密
 ④ core.auth         首次用 6 位连接码配对，之后免码认证 → 会话密钥
 ⑤ core.dmsdp        设备会话：能力交换、服务通告与启动、时间同步、保活
 ⑥ core.hisight      投屏控制（RTSP）+ 视频流（TCP）→ H.264 → platform.media 解码上屏
                      触摸、按键 ← app 采集 → core.hisight 封包回传
                      车机方向盘、媒体键 ← 前台窗口 / 媒体按钮会话（app.input.MediaKeys）→ 同一条回控连接上的按键包
 ⑦ core.dmsdp（实验） 声明了扬声器时：手机触发音频数据会话 → 车机开数据端口、发 SETUP → 手机连入推流
                      → 识别分帧、解密拆出 RTP 载荷，按路号分成媒体、导航等几路 → 每路一个 platform.media.AudioPlayer
                      焦点：手机的焦点消息 → platform.media.AudioFocusController 申请系统焦点；
                      车机别的应用抢走焦点 → 压低或静音我们的播放，并告诉手机暂停或恢复
 ⑧ core.dmsdp（实验） 声明了麦克风时：手机触发 → 车机回能力协商 → 手机发 SETUP → 车机开端口、回 200
                      → 手机连入 → platform.media.MicCapture 采集（AAC 时经 MicAacEncoder）→ RTP 打包加密上行
```

③～⑥ 之间谁先谁后、谁连谁，由 `core.session` 串起来，见下文「会话编排」。

无线连接的区别只在前半段：手机扫码后经经典蓝牙（RFCOMM）连上车机，控制握手、认证（RFCOMM 子通道）、
WiFi 数据通道协商都在蓝牙上做；车机在手机认证通过后开热点，把热点的网络信息和 TCP 端口经蓝牙告诉手机；
手机入网后连上的那条 TCP 等同有线的第一条控制连接，之后 ⑤⑥ 完全相同。蓝牙、WiFi 的系统接口由
`platform.bluetooth` / `platform.wifi` 适配（把它们接到会话平台动作上的执行器、调度和界面在 `app` 的 `wireless` 包），
交接顺序由 `core.session` 的 `WirelessReceiverSession` 串起来，
见 [`core/session/README.md`「无线连接」](../core/session/README.md#无线连接)。

连成功过的手机回连不用再扫码（自动连接手机，默认开）：手机蓝牙连上车机、或界面回到前台时，车机按 SDP 主动连手机的
蓝牙控制服务，控制握手后请手机为认证开 RFCOMM 子通道，再按手机给的通道号连上（拿不到系统接口时按 SDP 查），之后与扫码
相同，认证按手机报的 `has_pin` 走免码回连；热点等连上手机的蓝牙控制通道后才开。只拨用无线连成功过的手机，一次蓝牙连接里
回到界面只拨一次。调度和触发规则在 `app` 的 `WirelessController`、`ReconnectPolicy`；会话一侧是 `core.session` 的
`WirelessReceiverSession.startReconnect` 和 `core.nearby` 里车机作为发起方的控制通道。
早先的被动等待（车机发 BLE 广播、等手机连上来，载荷见 [`core/nearby/README.md`「BLE 广播与回连」](../core/nearby/README.md#ble-广播与回连)，
以及配合它的「补全手机蓝牙配对」）代码还在，已不再自动进入，之后删掉。

## 模块与依赖

```
app ─▶ platform ─▶ core:common
 └──▶ core:session ─▶ core:hisight / core:dmsdp / core:auth / core:nearby ─▶ core:common

tools:desktop ─▶ core:session        （开发工具，不进安装包）
```

- **core:\***：纯 Kotlin/JVM，不引用安卓类，在电脑上就能跑单元测试。只处理字节；收发、计时由调用方注入，方便测试。
- **core:session**：唯一同时依赖四个协议层的 core 模块，负责层间交接；另带一个基于 java.net 的阻塞 I/O 执行器。
- **tools:desktop**：在电脑的 JVM 上直接连真手机跑完整会话的调试程序，见 [`tools/desktop/README.md`](../tools/desktop/README.md)。
- **platform**：安卓系统接口的适配，见 [`platform/README.md`](../platform/README.md)。除 core:common 外还依赖
  core:hisight（media 包的接口直接用协议层的类型）和 core:nearby（只在内部用：GATT 服务端按 nearby 的布局挂服务）。
- **app**：界面、设置、把各层串成一次完整连接。

各 `core` 层的职责见对应目录下的 `README.md`。

## 会话编排

`core:session` 把四个协议层串成一次完整的有线连接，分两部分：

- **`ReceiverSession`（编排器）**：持有 nearby、auth、dmsdp、hisight 的会话对象，负责层间交接。它和各层一样不做 I/O：
  调用方把 I/O 结果交给它（监听开好了、连接接受了、收到了字节、连接被关了、出错了、到时间了），
  它返回一串按顺序执行的**动作**和**事件**。监听、连接、UDP socket 都用编排器分配的整数 id 标识。
  时间用参数 `nowMillis`（单调时钟）传入。
- **`SocketRunner`（执行器）**：用 java.net 阻塞 I/O 执行动作，安卓和电脑通用。

### 交接顺序

```
 车机（OpenHC）                                                  手机
 ────────────────────────────────────────────────────────────────────────────
 ① 开控制监听（默认通配地址、只认连到车机 IP 的连接）
    → ControlListening：此后才发 carInfo（USB 拉起不归本模块）
                                              ◀──── 连控制端口（第一条 TCP）
 ② nearby：明文 connectId ────▶               ◀──── 版本、摘要、加密开关……
                                              ◀──── 建 socket 请求：业务 19（hicarAuth）
 ③ 在车机 IP 上开认证监听（端口由系统分配），开好后回端口 ────▶
                                              ◀──── 连认证端口，设备握手
    auth：握手应答（首次配对带 6 位连接码 → ShowPinCode）────▶
    ◀────────── 认证消息往返（首次 PIN 配对 / 免码回连）──────────▶
    认证成功 → 16 字节会话密钥（Authenticated），留给 dmsdp 和 hisight
                                              ◀──── 建 socket 请求：业务 20（DMSDP）
    （认证握手之前到达：拒绝；握手之后、成功之前：先留着，成功后再回）
 ④ 在车机 IP 上开业务 20 监听，开好后回端口 ────▶
                                              ◀──── 连业务 20，发首轮协商
 ⑤ dmsdp：在车机 IP 上另开设备控制监听，开好后把端口写回业务 20 ────▶
                                              ◀──── 连设备控制端口
    明文 OPTIONS ────▶                        ◀──── 明文加密选择（含保活方式）
    明文回 200，之后全程加密（DeviceSessionReady）
    手机选 UDP 保活：绑车机 IP 的临时端口，connect 到「手机 : 它给的端口」，按周期发保活
    设备信息、服务列表、时间同步……            ◀──── 投屏端口通告（module 5）
 ⑥ hisight：连「手机 : 投屏端口」，ANNOUNCE、加密、OPTIONS、能力查询与选择、SETUP
    SETUP 应答（VideoNegotiated）→ 先连视频端口；视频连上后再连回控端口；
    两条都连上才发 PLAY                         ◀──── PLAY 200（PlayAccepted），视频推流
 ⑦ 视频帧在协议线程上同步交给 AccessUnitSink，不另外复制；第一帧时发 VideoStarted
```

几条容易错的规则：

- **先监听、后通告**：认证、业务 20、设备控制三个监听都是先开好、拿到实际端口，再把端口告诉手机；
  手机收到端口就会来连。手机为同一业务重复请求时，回同一个正在监听的端口，不开新监听、不回 0。
- **每个监听只接受一条连接**，接受之后监听保持打开到会话结束（重复请求还会拿到这个端口）。
- **认证在独立的 TCP 上**，不经过控制通道；认证成功之前不开业务 20 的监听。
- **会话密钥**：认证给出的密钥复制一份交给 dmsdp（业务 20 连上时）和 hisight（投屏端口通告时），
  两层各自持有副本，编排器随即清零自己的那份。
- **谁连谁**：nearby、认证、业务 20、设备控制都是手机连车机；UDP 保活、投屏控制、视频、回控都是车机连手机。
  音频数据（实验）走 TCP 时，手机先回 SETUP 的 200 再连车机在 `client_port=P--1` 里给的 P（第二个口是 RTCP，
  必须写 -1，表示不建）；车机只在 P 上监听。麦克风（实验）反过来由手机发 SETUP，车机开监听后在 200 里给
  `server_port=S--1`，手机再连 S；数据从车机流向手机。
- **声音不连累投屏**：音频、麦克风的监听、连接出错或被关只关这一条、出诊断，会话继续；音频数据对不上也只出诊断。
  车机去连的地址取自对应连接的对端：投屏三条连业务 20 连接的对端，UDP 保活连设备控制连接的对端。
- **视频与回控的顺序**：SETUP 应答同时给出视频和回控端口，但先只连视频，视频连上再连回控，两条都连上才发 PLAY。
  只要手机给了回控端口就连上，哪怕不发任何输入。
- 投屏端口重复通告同一个值时忽略，换了值按协议错误结束。

### 连接被手机关闭时

| 连接 | 处理 |
|---|---|
| 控制通道 | 认证成功、且业务 20 的监听已经开好并回过端口 → 继续；否则结束 |
| 认证连接 | 认证成功之后 → 正常；之前 → 认证失败，结束 |
| 业务 20 | 设备控制监听已经开过 → 继续；否则结束（残留半帧按协议错误结束） |
| 设备控制连接 | 投屏已开始 → 继续（保活随之停止）；否则结束 |
| 投屏控制连接 | PLAY 已被接受、视频连接还在 → 继续收视频，等视频连接结束再收尾；否则投屏结束 |
| 视频、回控连接 | 投屏结束 |
| 音频数据连接（实验） | 只影响声音，会话继续 |
| 麦克风数据连接（实验） | 只影响麦克风，会话继续 |

投屏层结束（手机要求结束、上表里的连接被关）时整个连接随之结束。任何一条还在用的连接、监听或 UDP 出错，
各层收到不合法的数据，保活超时，控制通道 5 秒内没握手完，都结束整个连接。

### 结束与关闭顺序

无论什么原因结束，都按同一个顺序收尾：

1. 投屏层：抬起还按着的触点和按键，加密已协商、控制连接还在时发一次 TEARDOWN（不等手机确认）；
2. 设备会话：结束进行中的事务；
3. 由上到下关闭全部资源：投屏控制、视频、回控 → 音频连接与监听 → 麦克风连接与监听 → UDP 保活、设备控制连接与监听、
   业务 20 连接与监听 → 认证连接与监听 → 控制通道与监听；
4. 清零会话密钥，最后输出 `Ended`。之后编排器的所有方法都返回空列表。

已经关闭的连接上的写直接丢掉（例如投屏控制连接先被手机关掉之后的 TEARDOWN）。

### 动作

| 动作 | 含义 | 执行结果交回 |
|---|---|---|
| `Listen(id, 通道, host, port, acceptId, requireLocalHost)` | 开 TCP 监听（port 0 由系统分配），只接受一条连接，连接的 id 是 `acceptId`；`requireLocalHost` 不为空时本端地址不是它的连接直接关掉 | `onListening(id, 实际端口)`、`onAccepted(acceptId, 对端 IP)` |
| `Connect(id, 通道, host, port)` | 主动连接 | `onConnected(id)` |
| `Write(id, bytes)` | 往连接写一段完整的线上字节 | 失败时 `onIoError` |
| `Close(id)` | 关闭监听、连接或 UDP socket | — |
| `OpenUdp(id, 通道, localHost, remoteHost, remotePort)` | 绑本机地址临时端口的 UDP，connect 到对端 | `onDatagram(id, …)` |
| `SendDatagram(id, bytes)` | 发一个数据报 | 失败时 `onIoError` |

另外：`onData(id, …)` 交回收到的字节，`onClosed(id)` 表示读到 EOF，`onIoError(id, 说明)` 表示任何 I/O 失败，
`onTick(now)` 推进定时（下次时间看 `nextTickAtMillis`），`stop(now)` 是本机结束。

### 事件

| 事件 | 时机 |
|---|---|
| `ControlListening(port)` | 控制监听已开好，可以发 carInfo |
| `PhoneConnected(phoneIp)` | 手机连上控制通道 |
| `ControlHandshakeCompleted(encrypted)` | 控制通道握手完成 |
| `AuthStarted(mode)` | 已回应认证握手：首次配对或免码回连 |
| `ShowPinCode(pin)` | 首次配对：把连接码显示给用户（toString 不含连接码） |
| `Authenticated(mode)` | 认证成功 |
| `DeviceSessionReady(keepAlive)` | 设备控制连接已加密，保活方式由手机选定 |
| `ProjectionRequested(port)` | 手机通告投屏端口，开始连投屏 |
| `VideoNegotiated(video)` | SETUP 完成：手机选定的宽高、帧率和可用输入 |
| `PlayAccepted` | 手机接受 PLAY |
| `VideoStarted(video)` | 收到第一帧（开始出画面） |
| `Diagnostic(layer, message)` | 不影响流程的信息：未处理的消息、被丢弃的数据、被拒绝的请求等 |
| `Ended(reason)` | 会话结束，只出现一次 |

结束原因（`EndReason`）：本机停止、控制通道握手超时、认证失败、协议错误（标明哪一层）、手机关闭了某条连接、
网络错误（标明哪条连接）、设备会话保活超时、投屏结束（投屏层给的原因）、视频输出失败、内部错误。

### 执行器

- 一个**协议线程**串行调用编排器的全部方法、执行全部写操作、回调全部事件；视频帧也在这个线程上交出。
- 每个监听、连接、UDP socket 一个线程：监听线程接受连接后接着当它的读线程，连接线程连上后同样接着读。
- 每条连接两块读缓冲轮流用：协议线程处理完一块才还回去，读数据不另外分配或复制；处理跟不上时读线程停下来，
  由 TCP 自己做流控，内存有上限。
- 监听开好、写失败这类同步结果，在当前这批动作执行完之后、下一个外部输入之前交回编排器，
  所以「监听开好」一定先于这个监听接受的连接。
- `stop()` 请求本机结束（非阻塞）；`close()` 等会话正常收尾，超时（例如某次写被卡住）就直接关掉全部 socket。
  协议线程退出前把还没关的资源按创建的反序关掉，再等所有线程退出。

## 车机固定参数

车型编号、品牌、名称等连接参数放在 `openhc-identity.json` 里。它不进仓库，
构建时从本机目录打进安装包（见 `app/build.gradle.kts`，格式见 `docs/identity.example.json`），应用每次开始连接时读它。改了车型编号，手机会把车机当成另一台车，要重新配对一次。
