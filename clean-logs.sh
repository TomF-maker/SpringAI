#!/bin/bash
# ============================================
# 知行信咨询助手 - 日志清理脚本
#
# 作用：删除 30 天前的日志文件。
#
# 为什么 logback 已经在删（maxHistory=30）还需要这个脚本：
#   logback 只删自己滚动出来的归档（app.2026-09-18.log.gz），管不到
#   deploy.sh 用 nohup 重定向出来的 stdout 文件，也兜住"logback 配置被改坏
#   导致归档没被清掉"的情况。两条腿走路，磁盘不会无声无息被写满。
#
# 不会删的：当天正在写的 app.log、stdout.log（按 mtime 判断，活跃文件永远新鲜；
#   另外脚本显式排除了这两个名字，避免进程还开着文件就被 unlink）。
#
# 安装（服务器上执行一次）：
#   crontab -e   →  0 3 * * * /opt/apps/springai/clean-logs.sh >> /opt/apps/springai/logs/clean.log 2>&1
# 手动验证（不真删，只打印会删什么）：
#   /opt/apps/springai/clean-logs.sh --dry-run
# ============================================
set -u

APP_DIR="${APP_DIR:-/opt/apps/springai}"
LOG_DIR="$APP_DIR/logs"
KEEP_DAYS="${KEEP_DAYS:-30}"

DRY_RUN=""
[ "${1:-}" = "--dry-run" ] && DRY_RUN="1"

if [ ! -d "$LOG_DIR" ]; then
    echo "[clean-logs] 日志目录不存在：$LOG_DIR"
    exit 0
fi

# -mtime +30 = 修改时间在 30 天以前。
# 只匹配日志类文件，避免误删目录里别的东西。
FOUND=$(find "$LOG_DIR" -maxdepth 1 -type f \
    \( -name '*.log' -o -name '*.log.gz' -o -name 'nohup.out' \) \
    ! -name 'app.log' ! -name 'stdout.log' \
    -mtime +"$KEEP_DAYS" 2>/dev/null)

if [ -z "$FOUND" ]; then
    echo "[clean-logs] $(date '+%F %T') 没有超过 ${KEEP_DAYS} 天的日志，无需清理（当前占用 $(du -sh "$LOG_DIR" 2>/dev/null | cut -f1)）"
    exit 0
fi

COUNT=0
while read -r f; do
    [ -z "$f" ] && continue
    if [ -n "$DRY_RUN" ]; then
        echo "[dry-run] 将删除 $f"
    else
        rm -f "$f" && echo "[clean-logs] 已删除 $f"
    fi
    COUNT=$((COUNT + 1))
done <<< "$FOUND"

echo "[clean-logs] $(date '+%F %T') 处理 $COUNT 个文件，当前占用 $(du -sh "$LOG_DIR" 2>/dev/null | cut -f1)"
