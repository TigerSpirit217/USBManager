#!/system/bin/sh

MODDIR=${0%/*}
DATA_DIR=/data/adb/usbmanager

mkdir -p "$DATA_DIR"
chmod 0700 "$DATA_DIR"
rm -f "$DATA_DIR/session"
rm -f "$DATA_DIR/choice_active"
rm -f "$DATA_DIR/hosts.db"
rm -rf "$DATA_DIR/adb_keys"
setprop persist.sys.usb.config none
setprop sys.usb.config none
setprop ctl.stop adbd
chmod 0755 "$MODDIR/service.sh" "$MODDIR/common.sh" "$MODDIR/usbmanagerctl.sh"
