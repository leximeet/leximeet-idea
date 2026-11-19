#!/usr/bin/env bash
# 已上架插件的后续版本，上传与 GitHub 草稿一致的签名 ZIP。
set +x
set -euo pipefail

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

# 只连接固定的 HTTPS API、不跟随重定向、不输出服务端响应体。
status="$(printf 'Authorization: Bearer %s\n' "$marketplace_token" | curl --disable --proto '=https' \
  --header @- --silent --output /dev/null --write-out '%{http_code}' --max-time 120 \
  --form-string 'xmlId=com.github.weilhuang.leximeetidea' --form "file=@$name;type=application/zip" \
  'https://plugins.jetbrains.com/api/updates/upload')" || {
  printf '%s\n' '无法连通 Marketplace。重试前请先在后台核对该版本是否收到。' >&2
  exit 1
}
if [[ $status != 2[0-9][0-9] ]]; then
  printf 'Marketplace 未接受上传（HTTP %s）。请在后台核对权限及版本状态。\n' "$status" >&2
  exit 1
fi
printf 'Marketplace 已接受 %s 的上传（HTTP %s），后续审核状态请在后台查看。\n' "$1" "$status"
