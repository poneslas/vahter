#!/usr/bin/env bash
# Perfetto-трасса для П2/П6: нажатие → первый кадр листа (полная цена кнопки).
#   probe/scripts/trace.sh [секунд=60]
# Во время записи человек жмёт боковую кнопку. Трасса — probe/reports/trace-<время>.perfetto-trace;
# открыть в ui.perfetto.dev (разбирается локально в браузере).
set -euo pipefail
cd "$(dirname "$0")/.."
SEC="${1:-60}"
OUT="reports/trace-$(date +%Y%m%d-%H%M%S).perfetto-trace"
DEV=/data/misc/perfetto-traces/vahter.perfetto-trace
mkdir -p reports

CFG="buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources { config { name: \"android.input.inputevent\" android_input_event_config { mode: TRACE_MODE_TRACE_ALL } } }
data_sources { config { name: \"android.surfaceflinger.frametimeline\" } }
data_sources { config { name: \"linux.process_stats\" process_stats_config { scan_all_processes_on_start: true } } }
data_sources { config { name: \"linux.ftrace\" ftrace_config {
  ftrace_events: \"sched/sched_switch\"
  atrace_categories: \"view\" atrace_categories: \"wm\" atrace_categories: \"am\"
  atrace_categories: \"input\" atrace_categories: \"gfx\"
  atrace_apps: \"ru.poneslas.vahter.probe\" } } }
duration_ms: $((SEC * 1000))"

echo "Запись ${SEC} с — жмите кнопку…"
echo "$CFG" | adb shell perfetto --txt -c - -o "$DEV"
adb pull "$DEV" "$OUT"
echo "$OUT"
