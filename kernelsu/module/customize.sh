#!/system/bin/sh

ui_print "- Installing USBManager KernelSU backend"
APK="$MODPATH/usbmanager.apk"
[ -f "$APK" ] || abort "! Embedded usbmanager.apk is missing"
set_perm "$APK" 0 0 0644

ui_print "- Installing USBManager UI APK"
if pm install -r "$APK" >/dev/null 2>&1; then
    ui_print "- USBManager UI APK installed"
else
    abort "! Failed to install USBManager UI APK"
fi

set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/common.sh" 0 0 0755
set_perm "$MODPATH/usbmanagerctl.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
