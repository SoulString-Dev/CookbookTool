#!/usr/bin/env bash
# 在无 root、$HOME 只读的环境里，把 Android 构建工具链装进工作区 .toolchain/。
# 幂等：已存在的部件会跳过下载。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/.toolchain"
mkdir -p "$TC"

# 真实 $HOME(/home/miu) 是只读的，而 Java 的 user.home 取自 passwd 而不是 $HOME，
# 所以必须用 _JAVA_OPTIONS 强制改写，否则 sdkmanager 会在 ~/.android/cache 上写失败。
export HOME="$TC/home"
export _JAVA_OPTIONS="-Duser.home=$TC/home"
mkdir -p "$HOME"

JDK_DIR="$TC/jdk"
SDK_DIR="$TC/android-sdk"
GRADLE_DIR="$TC/gradle-8.9"
DL="$TC/downloads"
mkdir -p "$DL"

JDK_URL="https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
CMDLINE_URLS=(
  "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  "https://dl.google.com/android/repository/commandlinetools-linux-10406996_latest.zip"
  "https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip"
)
GRADLE_URL="https://services.gradle.org/distributions/gradle-8.9-bin.zip"

fetch() { # url dest
  local url="$1" dest="$2"
  if [[ -s "$dest" ]]; then echo "[skip] $(basename "$dest") 已存在"; return 0; fi
  echo "[get ] $url"
  curl -fL --retry 3 --retry-delay 2 -o "$dest.part" "$url"
  mv "$dest.part" "$dest"
}

# 这台机器连 unzip 都没有，自己挑一个能解 zip 的工具
unzip_to() { # zip dest_dir
  local zip="$1" dest="$2"
  mkdir -p "$dest"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$zip" -d "$dest"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$zip" "$dest"
  else
    ( cd "$dest" && "$JAVA_HOME/bin/jar" xf "$zip" )
  fi
}

# ---------- JDK ----------
if [[ ! -x "$JDK_DIR/bin/java" ]]; then
  fetch "$JDK_URL" "$DL/jdk.tar.gz"
  echo "[jd ] 解压 JDK"
  rm -rf "$TC/jdk.tmp" && mkdir -p "$TC/jdk.tmp"
  tar -xzf "$DL/jdk.tar.gz" -C "$TC/jdk.tmp"
  inner="$(find "$TC/jdk.tmp" -maxdepth 1 -mindepth 1 -type d | head -1)"
  rm -rf "$JDK_DIR" && mv "$inner" "$JDK_DIR" && rm -rf "$TC/jdk.tmp"
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
echo "[ok  ] $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

# ---------- Gradle ----------
if [[ ! -x "$GRADLE_DIR/bin/gradle" ]]; then
  fetch "$GRADLE_URL" "$DL/gradle-8.9-bin.zip"
  echo "[gra] 解压 Gradle"
  rm -rf "$TC/gradle.tmp"
  unzip_to "$DL/gradle-8.9-bin.zip" "$TC/gradle.tmp"
  inner="$(find "$TC/gradle.tmp" -maxdepth 1 -mindepth 1 -type d | head -1)"
  rm -rf "$GRADLE_DIR" && mv "$inner" "$GRADLE_DIR" && rm -rf "$TC/gradle.tmp"
  chmod +x "$GRADLE_DIR/bin/gradle" 2>/dev/null || true
fi

# ---------- Android cmdline-tools ----------
if [[ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]]; then
  ok=0
  for u in "${CMDLINE_URLS[@]}"; do
    if fetch "$u" "$DL/cmdline-tools.zip"; then ok=1; break; fi
    rm -f "$DL/cmdline-tools.zip"
  done
  [[ $ok -eq 1 ]] || { echo "[err ] 无法下载 commandlinetools"; exit 1; }
  echo "[sdk] 解压 cmdline-tools"
  rm -rf "$SDK_DIR/cmdline-tools"
  unzip_to "$DL/cmdline-tools.zip" "$SDK_DIR/cmdline-tools"
  mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
  # zip 里的可执行位不一定被解压器保留，手动补上
  chmod -R +x "$SDK_DIR/cmdline-tools/latest/bin" 2>/dev/null || true
fi

export ANDROID_SDK_ROOT="$SDK_DIR"
export ANDROID_HOME="$SDK_DIR"
SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"

echo "[sdk] 接受许可"
yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 || true

echo "[sdk] 安装 platform-tools / platforms;android-35 / build-tools;35.0.0"
"$SDKMANAGER" --sdk_root="$SDK_DIR" "platform-tools" "platforms;android-35" "build-tools;35.0.0"

echo
echo "================ 工具链就绪 ================"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
"$GRADLE_DIR/bin/gradle" --version | grep -E '^(Gradle|JVM)'
ls "$SDK_DIR/platforms"
echo "==========================================="
echo "构建请用：scripts/build.sh"
