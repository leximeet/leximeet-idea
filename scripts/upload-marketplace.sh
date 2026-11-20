#!/usr/bin/env bash
# 已上架插件的后续版本，上传与 GitHub 草稿一致的签名 ZIP。
set +x
set -euo pipefail
umask 077

if [[ $# != 2 || $2 != /* || ! -d $2 || $1 == v0.0.1 ]]; then
  printf '%s\n' '此脚本供已上架插件的后续标签使用。用法：bash scripts/upload-marketplace.sh <标签> <签名产物目录>' >&2
  exit 2
fi

# 收下 Token 后立刻取消导出，后续子进程只从标准输入得到请求头。
unset marketplace_token
marketplace_token="${PUBLISH_TOKEN:-}"
unset PUBLISH_TOKEN LEXIMEET_SIGNING_CERTIFICATE LEXIMEET_SIGNING_PRIVATE_KEY LEXIMEET_SIGNING_PASSWORD
if [[ -z $marketplace_token || $marketplace_token == *[[:space:][:cntrl:]]* ]]; then
  printf '%s\n' 'Marketplace Token 为空或含有无效字符。' >&2
  exit 2
fi

root="$(cd -- "${BASH_SOURCE[0]%/*}/.." && pwd -P)"
cd -- "$2"
version="$(python3 "$root/scripts/check-release-archive.py" --tag "$1" --version-only)"
name="leximeet-idea-$version.zip"
expected="$(python3 "$root/scripts/check-release-archive.py" --archive "$name")"
if ! printf '%s\n' "$expected" | cmp -s "$name.sha256" -; then
  printf '%s\n' '签名 ZIP 的校验和与发布附件不一致。' >&2
  exit 1
fi

# 只连接固定的 HTTPS API、不跟随重定向。响应体仅用于核对本次提交回执。
receipt="$(mktemp)"
trap 'rm -f -- "$receipt"' EXIT
status="$(printf 'Authorization: Bearer %s\n' "$marketplace_token" | curl --disable --proto '=https' \
  --header @- --header 'Accept: application/json' --silent --output "$receipt" --write-out '%{http_code}' --max-time 120 \
  --form-string 'xmlId=com.github.weilhuang.leximeetidea' --form "file=@$name;type=application/zip" \
  'https://plugins.jetbrains.com/api/updates/upload')" || {
  printf '%s\n' '无法连通 Marketplace。重试前请先在后台核对该版本是否收到。' >&2
  exit 1
}
if [[ $status != 2[0-9][0-9] ]]; then
  printf 'Marketplace 未接受上传（HTTP %s）。请在后台核对权限及版本状态。\n' "$status" >&2
  exit 1
fi
python3 - "$receipt" "$version" "$status" <<'PY'
import json
from pathlib import Path
import sys

path, version, status = sys.argv[1:]
try:
    receipt = json.loads(Path(path).read_text(encoding="utf-8"))
    update_id = receipt["id"]
    plugin_id = receipt["pluginId"]
    if receipt["version"] != version or any(
        type(value) is not int or value <= 0 for value in (update_id, plugin_id)
    ):
        raise ValueError("回执版本或 ID 不匹配")
except (OSError, KeyError, TypeError, ValueError):
    # 避免响应中的任意字段、请求详情和账号信息进入公开 CI 日志。
    sys.exit(f"Marketplace 返回 HTTP {status}，但未能确认版本回执。重试前请在后台核对。")
print(f"Marketplace 已接收 {version}（HTTP {status}，插件 ID {plugin_id}，更新 ID {update_id}），审核状态以后台为准。")
PY
