#!/usr/bin/env bash
# 只在仓库外生成新证书和加密私钥。OpenSSL 交互读取口令。
set +x
set -euo pipefail
umask 077

if [[ $# != 1 || $1 != /* ]]; then
  printf '%s\n' '用法：bash scripts/generate-signing-keys.sh /绝对路径/新目录' >&2
  exit 2
fi

unset key_password confirmation
key_password="${LEXIMEET_SIGNING_PASSWORD:-}"
unset LEXIMEET_SIGNING_CERTIFICATE LEXIMEET_SIGNING_PRIVATE_KEY LEXIMEET_SIGNING_PASSWORD PUBLISH_TOKEN
root="$(cd -- "${BASH_SOURCE[0]%/*}/.." && pwd -P)"
requested="${1%/}"
parent="$(cd -- "$(dirname -- "$requested")" && pwd -P)"
name="$(basename -- "$requested")"
destination="$parent/$name"
case "$destination/" in
  "$root/"*) printf '%s\n' '签名材料必须保存在插件仓库之外。' >&2; exit 2 ;;
esac
if [[ -z $key_password ]]; then
  IFS= read -rs -p '签名口令 ' key_password
  printf '\n'
  IFS= read -rs -p '确认签名口令 ' confirmation
  printf '\n'
  if [[ -z $key_password || $key_password != "$confirmation" ]]; then
    printf '%s\n' '口令为空或两次输入不一致。' >&2
    exit 2
  fi
fi
unset confirmation
# mkdir 在已存在时失败，避免覆盖之前的密钥。
mkdir -m 700 -- "$destination"
# 仅让 OpenSSL 读到临时口令，目录创建和证书检查无需该变量。
LEXIMEET_SIGNING_PASSWORD="$key_password" openssl genpkey -aes-256-cbc -algorithm RSA -pkeyopt rsa_keygen_bits:4096 \
  -pass env:LEXIMEET_SIGNING_PASSWORD -out "$destination/private.pem"
LEXIMEET_SIGNING_PASSWORD="$key_password" openssl req -new -x509 -sha256 -days 365 -key "$destination/private.pem" \
  -passin env:LEXIMEET_SIGNING_PASSWORD -subj '/CN=LexiMeet IntelliJ Plugin Signing' \
  -out "$destination/chain.crt"
unset key_password
openssl x509 -in "$destination/chain.crt" -noout -fingerprint -sha256 -enddate
printf '已写入加密私钥与证书。%s\n' "$destination"
