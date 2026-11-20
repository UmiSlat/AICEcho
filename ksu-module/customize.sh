#!/system/bin/sh

[ "${ARCH:-}" = arm64 ] || abort "! This module requires arm64"

if [ "${KSU:-}" = true ]; then
  case "${KSU_VER_CODE:-}" in
    ''|*[!0-9]*) abort "! KernelSU version code is missing or invalid" ;;
  esac
  [ "$KSU_VER_CODE" -ge 32389 ] || abort "! KernelSU v3.2.0 or later is required"
  ui_print "- Installing AICEcho PMm metamodule for KernelSU"
elif [ "${APATCH:-}" = true ]; then
  grep -q 'metamount.sh' /data/adb/apd || abort "! Update APatch to a version with metamodule support"
  ui_print "- Installing AICEcho PMm metamodule for APatch"
elif [ -n "${MAGISK_VER_CODE:-}" ]; then
  sed -i '/^metamodule=/d' "$MODPATH/module.prop"
  rm -f "$MODPATH/metamount.sh"
  ui_print "- Installing AICEcho PMm module for Magisk"
else
  abort "! KernelSU, APatch or Magisk is required"
fi

if [ "${API:-0}" -lt 35 ]; then
  ui_print "! This module is intended for Android 15 or later"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/vendor" 0 0 0755 0644 u:object_r:vendor_file:s0
set_perm "$MODPATH/bin/aicecho_injector" 0 0 0755 u:object_r:system_file:s0
set_perm "$MODPATH/service.sh" 0 0 0755 u:object_r:system_file:s0
[ ! -f "$MODPATH/metamount.sh" ] || set_perm "$MODPATH/metamount.sh" 0 0 0755 u:object_r:system_file:s0
set_perm "$MODPATH/uninstall.sh" 0 0 0755 u:object_r:system_file:s0

mkdir -p /data/adb/aicecho_pmm
chmod 0700 /data/adb/aicecho_pmm
: > /data/adb/aicecho_pmm/disabled
printf '%s\n' disabled > /data/adb/aicecho_pmm/state
printf '%s\n' 'Patch disabled after installation' > /data/adb/aicecho_pmm/detail

if [ "${KSU:-}" = true ]; then
  ui_print "- Patch installed disabled; use KernelSU soft reboot, then enable it in the app"
else
  ui_print "- Patch installed disabled; reboot, then enable it in the app"
fi
