#!/system/bin/sh

MODDIR=${0%/*}
if [ -f "$MODDIR/metamount.sh" ]; then
  sh "$MODDIR/metamount.sh" cleanup
fi
