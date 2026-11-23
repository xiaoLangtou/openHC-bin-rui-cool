# usbraise —— macOS USB 拉起工具

配合 `openhc-desktop` 在 Mac 上跑完整的有线 HiCar 会话：**Mac 当车机，真手机直连**，
所有协议数据（控制通道、认证、DMSDP、投屏）都在桌面上跑，不用上车。

## 一次完整会话

终端 1——启动桌面车机：

```sh
./gradlew :tools:desktop:installDist
tools/desktop/build/install/openhc-desktop/bin/openhc-desktop \
  --identity app/src/main/assets/openhc-identity.json \
  --trust-dir ~/.openhc-desktop/trust \
  --ip 192.168.66.150 --port 32400 \
  --bt 02:12:34:56:78:9A \
  --width 1920 --height 720 --fps 60 \
  --video-out /tmp/openhc-$(date +%Y%m%d-%H%M%S).h264 \
  --carinfo-out /tmp/carinfo.bin
```

等出现「可以开始 USB 拉起」横幅。

终端 2——发 carInfo、配网卡：

```sh
cd tools/desktop/usbraise
clang -O2 -Wall -o usbraise usbraise.c -I/opt/homebrew/include -L/opt/homebrew/lib -lusb-1.0
./usbraise /tmp/carinfo.bin          # 发 carInfo，等手机重枚举成 USB 网卡
sudo ./setup-usbnet.sh               # 给 NCM 网卡配 192.168.66.150（要 sudo）
```

手机随即连上控制端口。首次配对在手机上输入连接码（openhc-desktop 醒目显示 6 位），
之后同一身份（同一份 identity + `--bt`）免码回连。

## 手机状态要求

`./usbraise --list` 看状态：

| 产品号 | 状态 | 能否拉起 |
|---|---|---|
| 0x107D / 0x107E / 0x107F | 普通状态（MTP/仅充电） | ✓ 发 carInfo |
| 0x5000 | 开着 USB 调试 | ✗ 手机不理，先关 USB 调试 |
| 0x1101 | HDC（鸿蒙调试） | ✗ 同上，关掉调试/断开授权 |
| 变化后含 CDC/NCM 接口 | 已重枚举成网卡 | 直接配 IP，不用再发 |

发完 carInfo 手机约 1～2 秒重枚举；15 秒没等到就检查手机屏幕弹窗。

## 与车机的差别

- 协议核心（core/*）同一份代码：控制通道、认证、DMSDP、HiSight 完全一致，未知消息
  （UnhandledDeviceData，含 hex 倾印）照常打日志——抓 515/卡片数据帧在桌面就能做。
- 没有的部分：热点/蓝牙（有线模式本来不用）、ECARX 媒体中心（推音乐卡片要上车联调）、
  音频出声（桌面版把会话跑通，音频数据可另加落盘）。
- `192.168.66.x` 地址是配给 Mac 的 NCM 网卡的，和车机 USB 网卡语义相同。
