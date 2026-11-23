#!/usr/bin/env python3
"""公开代码树检查：仓库里不许出现凭据（见 SECURITY.md）。

连接参数 openhc-identity.json、安装包签名配置、证书、密钥库、私钥都不进仓库。
只查 git 管着的文件，发现问题就逐条列出并以非 0 退出。CI 在构建前跑，本地提交前也可以跑。
"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 证书、密钥库、私钥、安装包：一律不进仓库。
BLOCKED_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pk8", ".pem", ".key", ".apk", ".aab"}
# 连接参数文件、本机配置；名字里带 signing.properties 的签名配置。
BLOCKED_NAMES = {"openhc-identity.json", "local.properties"}
BLOCKED_NAME_PARTS = ("signing.properties",)
PRIVATE_KEY_BLOCK = re.compile(rb"-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----")
# 只有这份示例可以带连接参数的结构，而且密钥必须是全零占位值。
EXAMPLE = "docs/identity.example.json"
ZERO_BASE64 = re.compile(r"^A+=*$")


def tracked_files():
    out = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT)
    return [p for p in out.decode("utf-8").split("\0") if p]


def problems(path):
    name = Path(path).name
    if name in BLOCKED_NAMES or any(part in name for part in BLOCKED_NAME_PARTS):
        yield "这个文件不许进仓库"
    if Path(path).suffix.lower() in BLOCKED_SUFFIXES:
        yield "证书、密钥库、私钥或安装包不许进仓库"
    file = ROOT / path
    if not file.is_file():
        return
    data = file.read_bytes()
    if PRIVATE_KEY_BLOCK.search(data):
        yield "含 PEM 私钥"
    # 连接参数文件是 JSON：只查 JSON，代码和测试里提到字段名不算。
    if Path(path).suffix.lower() == ".json" and b'"nearbyKeys"' in data:
        if path != EXAMPLE:
            yield "是连接参数文件（带 nearbyKeys），只有示例文件可以有"
        else:
            keys = json.loads(data.decode("utf-8")).get("nearbyKeys") or {}
            if not keys or not all(ZERO_BASE64.match(str(v)) for v in keys.values()):
                yield "示例文件里的密钥必须是全零占位值"


def main():
    found = [(path, why) for path in tracked_files() for why in problems(path)]
    for path, why in found:
        print(f"{path}：{why}", file=sys.stderr)
    if found:
        print(f"发现 {len(found)} 处凭据问题，见 SECURITY.md。", file=sys.stderr)
        return 1
    print("公开代码树检查通过：没有凭据文件。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
