#!/system/bin/sh

MODDIR=${0%/*}
STATE_DIR=/data/adb/aicecho_pmm
STATE_FILE=$STATE_DIR/state
DETAIL_FILE=$STATE_DIR/detail
DISABLED_FILE=$STATE_DIR/disabled
LOCK_DIR=$STATE_DIR/operation.lock
INJECTOR=$MODDIR/bin/aicecho_injector
LIBRARY=/vendor/lib64/libaicecho_pmm.so
ENABLED_PROPERTY=vendor.aicecho.pmm.enabled

mkdir -p "$STATE_DIR"
chmod 0700 "$STATE_DIR"

record_state() {
  printf '%s\n' "$1" > "$STATE_FILE"
  printf '%s\n' "$2" > "$DETAIL_FILE"
}

status() {
  state=$(sed -n '1p' "$STATE_FILE" 2>/dev/null)
  detail=$(sed -n '1p' "$DETAIL_FILE" 2>/dev/null)
  [ -n "$state" ] || state=waiting
  [ -n "$detail" ] || detail="Waiting for the NFC HAL"
  printf 'state=%s\n' "$state"
  printf 'detail=%s\n' "$detail"
}

find_hal() {
  for name in \
    android.hardware.nfc-service-st \
    android.hardware.nfc-service-nxp \
    android.hardware.nfc-service.st \
    android.hardware.nfc-service.nxp \
    android.hardware.nfc-service; do
    pid=$(pidof "$name" 2>/dev/null)
    [ -n "$pid" ] && { printf '%s\n' "${pid%% *}"; return 0; }
  done
  for process in /proc/[0-9]*/cmdline; do
    command=$(tr '\000' ' ' < "$process" 2>/dev/null)
    case "$command" in
      *android.hardware.nfc-service*)
        process=${process#/proc/}
        printf '%s\n' "${process%/cmdline}"
        return 0
        ;;
    esac
  done
  return 1
}

set_patch_enabled() {
  requested=$1
  [ "$(getprop "$ENABLED_PROPERTY")" = "$requested" ] && return 0
  setprop "$ENABLED_PROPERTY" "$requested" 2>/dev/null
  [ "$(getprop "$ENABLED_PROPERTY")" = "$requested" ]
}

inject_once() {
  [ -f "$DISABLED_FILE" ] && { record_state disabled "Patch disabled"; return 0; }
  [ -x "$INJECTOR" ] || { record_state error "Injector is missing"; return 1; }
  [ -f "$LIBRARY" ] || { record_state error "Patch library is missing"; return 1; }

  pid=$(find_hal) || { record_state waiting "Waiting for the NFC HAL"; return 1; }
  if grep -q 'libaicecho_pmm.so' "/proc/$pid/maps" 2>/dev/null; then
    record_state active "Active in NFC HAL process $pid"
    printf '%s\n' "$pid" > "$STATE_DIR/pid"
    return 0
  fi

  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    record_state injecting "Another injection attempt is running"
    return 1
  fi
  trap 'rmdir "$LOCK_DIR" 2>/dev/null' EXIT INT TERM
  record_state injecting "Injecting into NFC HAL process $pid"
  output=$($INJECTOR "$pid" "$LIBRARY" 2>&1)
  code=$?
  if [ "$code" -eq 0 ] && grep -q 'libaicecho_pmm.so' "/proc/$pid/maps" 2>/dev/null; then
    printf '%s\n' "$pid" > "$STATE_DIR/pid"
    record_state active "Active in NFC HAL process $pid"
    result=0
  else
    record_state error "Injection failed ($code): ${output:-no diagnostic}"
    result=1
  fi
  rmdir "$LOCK_DIR" 2>/dev/null
  trap - EXIT INT TERM
  return "$result"
}

monitor() {
  last_pid=
  while true; do
    if [ -f "$DISABLED_FILE" ]; then
      if set_patch_enabled 0; then
        record_state disabled "Patch disabled"
      else
        record_state error "Could not update the runtime PMm switch"
      fi
    else
      if ! set_patch_enabled 1; then
        record_state error "Could not update the runtime PMm switch"
        sleep 5
        continue
      fi
      current_pid=$(find_hal 2>/dev/null)
      if [ -n "$current_pid" ] && { [ "$current_pid" != "$last_pid" ] ||
          ! grep -q 'libaicecho_pmm.so' "/proc/$current_pid/maps" 2>/dev/null; }; then
        inject_once
      elif [ -z "$current_pid" ]; then
        record_state waiting "Waiting for the NFC HAL"
      fi
      last_pid=$current_pid
    fi
    sleep 5
  done
}

case "${1:-monitor}" in
  status)
    status
    ;;
  enable)
    rm -f "$DISABLED_FILE"
    if ! set_patch_enabled 1; then
      record_state error "Could not enable the runtime PMm switch"
      exit 1
    fi
    record_state waiting "Enabled; waiting for the NFC HAL"
    inject_once >/dev/null 2>&1 || true
    ;;
  disable)
    if ! set_patch_enabled 0; then
      record_state error "Could not disable the runtime PMm switch"
      exit 1
    fi
    : > "$DISABLED_FILE"
    record_state disabled "Patch disabled"
    ;;
  inject)
    inject_once
    ;;
  monitor)
    monitor
    ;;
  *)
    printf 'usage: %s {status|enable|disable|inject|monitor}\n' "$0" >&2
    exit 64
    ;;
esac
