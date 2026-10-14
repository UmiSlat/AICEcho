#!/system/bin/sh

ui_print "- Installing AICEcho PMm patch"

if [ "${API:-0}" -lt 35 ]; then
  ui_print "! This module is intended for Android 15 or later"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/vendor/lib64/libaimesim_pmm.so" 0 0 0644 u:object_r:vendor_file:s0
set_perm "$MODPATH/bin/aimesim_injector" 0 0 0755 u:object_r:system_file:s0
set_perm "$MODPATH/service.sh" 0 0 0755 u:object_r:system_file:s0

mkdir -p /data/adb/aimesim_pmm
chmod 0700 /data/adb/aimesim_pmm
: > /data/adb/aimesim_pmm/disabled
printf '%s\n' disabled > /data/adb/aimesim_pmm/state
printf '%s\n' 'Patch disabled after installation' > /data/adb/aimesim_pmm/detail

ui_print "- Module installed disabled; reboot once, then enable it in the app"
