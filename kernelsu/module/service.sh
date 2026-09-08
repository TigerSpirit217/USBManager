#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"

handle_connection() {
    log_msg "Computer USB inserted role=$(usb_role) type=$(usb_type); forcing charge-only before chooser"
    apply_usb_config none 0
    rm -f "$CHOICE_FILE"
    if is_device_locked; then
        log_msg "Device is locked or asleep; chooser suppressed and charge-only retained"
        locked_enforced=1
        return
    fi
    token=$((( $(date +%s) + $$ ) % 2000000000))
    echo "$token" > "$SESSION_FILE"
    chmod 0600 "$SESSION_FILE"
    launch_chooser "$token"
}

process_ui_command() {
    for command_path in \
        /data/media/*/Android/data/com.tiger.usbmanager/files/command \
        /data/user/*/com.tiger.usbmanager/files/command; do
        [ -f "$command_path" ] || continue
        processing=$DATA_DIR/command.$$
        mv -f "$command_path" "$processing" 2>/dev/null || continue
        IFS='|' read -r action token mode adb unused < "$processing"
        rm -f "$processing"
        case "$action" in
            apply) "$MODDIR/usbmanagerctl.sh" apply "$token" "$mode" "$adb" ;;
            close) "$MODDIR/usbmanagerctl.sh" close "$token" ;;
            reboot) "$MODDIR/usbmanagerctl.sh" reboot "$token" "$mode" ;;
            *) log_msg "Ignored malformed UI response" ;;
        esac
    done
}

log_msg "KernelSU manual-choice service started"
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done
apply_usb_config none 0
log_msg "Boot policy enforced: charge-only with ADB disabled"
rm -f /data/media/*/Android/data/com.tiger.usbmanager/files/command \
    /data/user/*/com.tiger.usbmanager/files/command 2>/dev/null
active=0
last_skip=
locked_enforced=0
while true; do
    process_ui_command
    if usb_present; then
        if [ "$active" = "0" ]; then
            USBMANAGER_SKIP_REASON=
            if is_data_peer; then
                active=1
                last_skip=
                handle_connection
            elif [ "$USBMANAGER_SKIP_REASON" != "$last_skip" ]; then
                log_msg "USB ignored reason=${USBMANAGER_SKIP_REASON:-unknown} role=$(usb_role) type=$(usb_type)"
                last_skip=$USBMANAGER_SKIP_REASON
            fi
        elif is_device_locked; then
            if [ "$locked_enforced" = "0" ]; then
                dismiss_chooser
                rm -f "$CHOICE_FILE"
                apply_usb_config none 0
                log_msg "Lock screen detected during USB session; charge-only enforced"
                locked_enforced=1
            fi
        else
            locked_enforced=0
        fi
    else
        if [ "$active" = "1" ]; then
            log_msg "USB disconnected; returning to charge-only"
            dismiss_chooser
            apply_usb_config none 0
        fi
        active=0
        last_skip=
        locked_enforced=0
    fi
    sleep 0.2
done
