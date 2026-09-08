#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"

case "$1" in
    apply)
        token=$2
        mode=$3
        adb=$4
        [ -f "$SESSION_FILE" ] || { echo no_session; exit 2; }
        expected_token=$(cat "$SESSION_FILE" 2>/dev/null)
        [ "$token" = "$expected_token" ] || { echo bad_token; exit 3; }
        case "$mode" in none|mtp|ptp|rndis) ;; *) echo bad_mode; exit 4 ;; esac
        [ "$adb" = "1" ] || adb=0
        apply_usb_config "$mode" "$adb"
        rm -f "$SESSION_FILE"
        echo "$mode|$adb" > "$CHOICE_FILE"
        chmod 0600 "$CHOICE_FILE"
        echo applied
        ;;
    close)
        token=$2
        [ -f "$SESSION_FILE" ] || exit 0
        expected_token=$(cat "$SESSION_FILE" 2>/dev/null)
        [ "$token" = "$expected_token" ] || { echo bad_token; exit 3; }
        rm -f "$SESSION_FILE"
        rm -f "$CHOICE_FILE"
        apply_usb_config none 0
        echo closed
        ;;
    reboot)
        token=$2
        target=$3
        [ -f "$SESSION_FILE" ] || { echo no_session; exit 2; }
        expected_token=$(cat "$SESSION_FILE" 2>/dev/null)
        [ "$token" = "$expected_token" ] || { echo bad_token; exit 3; }
        case "$target" in system|recovery|bootloader|fastboot) ;;
            *) echo bad_reboot_target; exit 5 ;;
        esac
        rm -f "$SESSION_FILE" "$CHOICE_FILE"
        log_msg "Reboot requested target=$target"
        sync
        if [ "$target" = "system" ]; then
            reboot
            setprop sys.powerctl reboot
        else
            reboot "$target"
            setprop sys.powerctl "reboot,$target"
        fi
        ;;
    status)
        echo "role=$(usb_role)"
        echo "type=$(usb_type)"
        echo "present=$(usb_present && echo 1 || echo 0)"
        echo "mode=$(getprop sys.usb.config)"
        echo "adbd=$(getprop init.svc.adbd)"
        ;;
    *)
        echo "usage: $0 apply|close|reboot|status"
        exit 1
        ;;
esac
