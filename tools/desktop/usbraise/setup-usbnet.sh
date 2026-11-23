#!/bin/bash
# setup-usbnet —— 手机重枚举成 USB 网卡后，给 macOS 的 NCM 网卡配 IP。
#
# 用法（要 sudo，因为 ifconfig）：
#   sudo ./setup-usbnet.sh                 # 自动找 NCM 网卡，配 192.168.66.150
#   sudo ./setup-usbnet.sh en7             # 指定网卡
#   sudo ./setup-usbnet.sh en7 192.168.66.150
#
# IP 必须与 openhc-desktop 的 --ip 一致（车机地址，14 个字符：192.168.66.100～253）。

set -euo pipefail

CAR_IP="${2:-192.168.66.150}"

if [[ $# -ge 1 ]]; then
  IFACE="$1"
else
  # 在硬件端口里找 NCM/USB 网卡（手机重枚举出来的接口通常叫 "USB 10/100/1000 LAN" 或类似）。
  IFACE=$(networksetup -listallhardwareports | awk '/Hardware Port/{p=$0} /Device:/&&p{d=$2} p~/NCM|USB.*LAN|CDC/{print d; exit}')
  if [[ -z "${IFACE:-}" ]]; then
    echo "没找到 NCM/USB 网卡。现在的网络接口："
    ifconfig -l
    echo "手机可能还没重枚举；或用 sudo ./setup-usbnet.sh <网卡名> 手动指定。"
    exit 1
  fi
fi

echo "给 $IFACE 配 $CAR_IP/24"
ifconfig "$IFACE" inet "$CAR_IP" netmask 255.255.255.0 up
echo "完成。手机会连 $CAR_IP 上的控制端口（openhc-desktop 的 --port）。"
