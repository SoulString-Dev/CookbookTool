#!/usr/bin/env bash
# 统一构建入口：把 JDK/SDK/Gradle 缓存都指到工作区内（真实 $HOME 只读）。
#
#   scripts/build.sh assembleDebug      # 出 APK
#   scripts/build.sh test               # 跑 JVM 单测
#   scripts/build.sh testDebugUnitTest --tests 'org.miu.cookbook.*'
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/.toolchain"

if [[ ! -x "$TC/jdk/bin/java" ]]; then
  echo "工具链缺失，先跑：bash scripts/setup_toolchain.sh" >&2
  exit 1
fi

export JAVA_HOME="$TC/jdk"
export ANDROID_SDK_ROOT="$TC/android-sdk"
export ANDROID_HOME="$TC/android-sdk"
# Gradle / Kotlin / adb 都往 $HOME 写，重定向到可写目录
export HOME="$TC/home"
export GRADLE_USER_HOME="$TC/gradle-home"
# Java 的 user.home 取自 passwd 而非 $HOME，也必须一起改写，否则任何 Java 进程写 ~ 都会失败
export _JAVA_OPTIONS="-Duser.home=$HOME"
mkdir -p "$HOME" "$GRADLE_USER_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

if [[ -x "$ROOT/gradlew" ]]; then
  GRADLE=( "$ROOT/gradlew" )
else
  GRADLE=( "$TC/gradle-8.9/bin/gradle" )
fi

if [[ $# -eq 0 ]]; then
  set -- assembleDebug
fi

exec "${GRADLE[@]}" -p "$ROOT" "$@"
