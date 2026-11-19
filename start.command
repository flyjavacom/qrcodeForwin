#!/bin/bash

cd -- "$(dirname -- "$0")" || exit 1

# GUI launchers do not inherit shell profile PATH entries.
export PATH="/opt/homebrew/bin:/usr/local/bin:/opt/local/bin:$PATH"
if /usr/sbin/networksetup -getsecurewebproxy Wi-Fi 2>/dev/null | /usr/bin/grep -q 'Enabled: Yes'; then
    export HTTP_PROXY="http://127.0.0.1:10808"
    export HTTPS_PROXY="$HTTP_PROXY"
    export ALL_PROXY="$HTTP_PROXY"
fi

jar='qrcodeForwin-1.0-SNAPSHOT.jar'
if [[ ! -f "$jar" ]]; then
    printf '找不到 %s，请把 JAR 和 start.command 放在同一个文件夹。\n' "$jar" >&2
    exit 1
fi

java_bin=java
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    java_bin="$JAVA_HOME/bin/java"
fi

if ! "$java_bin" -version >/dev/null 2>&1; then
    printf '找不到可用的 Java，请安装 Java 后重新双击 start.command。\n' >&2
    exit 1
fi

exec "$java_bin" -jar "$jar"
