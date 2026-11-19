#!/usr/bin/env bash
# 签名并验证已通过测试的 ZIP，供本机首次上架或标签工作流复用。
set +x
set -euo pipefail
umask 077

if [[ ($# != 2 && $# != 4) || ${1:-} != /* || ${2:-} != /*.zip ]]; then
  printf '%s\n' '用法：bash scripts/sign-release.sh /候选包.zip /新签名包.zip [/证书.crt /加密私钥.pem]' >&2
  exit 2
fi
if [[ $# == 4 && ($3 != /* || $4 != /*) ]]; then
  printf '%s\n' '证书与私钥需要使用绝对路径。' >&2
  exit 2
fi
if [[ -e $2 || -L $2 || ! -d ${2%/*}/ ]]; then
  printf '%s\n' '签名包路径已存在，或输出目录不存在。' >&2
  exit 2
fi

root="$(cd -- "${BASH_SOURCE[0]%/*}/.." && pwd -P)"
cd "$root"
# 校验与解析依赖的进程无需接触签名材料，也不读取市场 Token。
without_credentials() (
  unset LEXIMEET_SIGNING_CERTIFICATE LEXIMEET_SIGNING_PRIVATE_KEY LEXIMEET_SIGNING_PASSWORD PUBLISH_TOKEN
  "$@"
)
signer="$root/build/leximeet/release-tools/marketplace-zip-signer-cli.jar"
without_credentials python3 scripts/check-release-archive.py --archive "$1"
if [[ ! -f $signer ]]; then
  without_credentials ./gradlew --no-daemon --no-configuration-cache prepareReleaseSigner
fi
java_executable="${JAVA_HOME:+$JAVA_HOME/bin/}java"
unset PUBLISH_TOKEN
if [[ $# == 4 ]]; then
  # 本机模式由 Java 控制台无回显读取口令；不复用 shell 中的同名变量。
  unset LEXIMEET_SIGNING_CERTIFICATE LEXIMEET_SIGNING_PRIVATE_KEY LEXIMEET_SIGNING_PASSWORD
fi
"$java_executable" -cp "$signer" scripts/ReleaseArchiveSigner.java "$@"
without_credentials python3 scripts/check-release-archive.py --archive "$2" --write-checksum
