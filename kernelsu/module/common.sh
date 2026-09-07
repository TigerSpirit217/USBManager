#!/system/bin/sh

MODDIR=${MODDIR:-${0%/*}}
DATA_DIR=/data/adb/usbmanager
SESSION_FILE=$DATA_DIR/session
CHOICE_FILE=$DATA_DIR/choice_active
LOG_FILE=$DATA_DIR/usbmanager.log
APP_COMPONENT=com.tiger.usbmanager/.ui.UsbChooserActivity

mkdir -p "$DATA_DIR"
chmod 0700 "$DATA_DIR" 2>/dev/null

log_msg() {
    if [ -f "$LOG_FILE" ]; then
        size=$(wc -c < "$LOG_FILE" 2>/dev/null)
        [ "${size:-0}" -gt 524288 ] && mv -f "$LOG_FILE" "$LOG_FILE.1"
    fi
    echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG_FILE"
    log -t USBManagerKSU "$*" 2>/dev/null
}

first_readable_value() {
    for path in "$@"; do
        [ -r "$path" ] || continue
        value=$(cat "$path" 2>/dev/null | head -n 1)
        [ -n "$value" ] && { printf '%s' "$value"; return 0; }
    done
    return 1
}

usb_role() {
    for path in /sys/class/usb_role/*/role /sys/class/typec/port*/data_role; do
        [ -r "$path" ] || continue
        value=$(cat "$path" 2>/dev/null | head -n 1)
        [ -n "$value" ] && { printf '%s' "$value"; return 0; }
    done
    printf 'unknown'
}

usb_type() {
    value=$(first_readable_value \
        /sys/class/power_supply/usb/usb_type \
        /sys/class/power_supply/USB/usb_type \
        /sys/class/power_supply/pc_port/usb_type 2>/dev/null)
    selected=$(printf '%s' "$value" | sed -n 's/.*\[\([^]]*\)\].*/\1/p')
    [ -n "$selected" ] && printf '%s' "$selected" || printf '%s' "${value:-unknown}"
}

usb_present() {
    for path in /sys/class/power_supply/usb/online /sys/class/power_supply/USB/online /sys/class/power_supply/pc_port/online; do
        [ -r "$path" ] || continue
        [ "$(cat "$path" 2>/dev/null)" = "1" ] && return 0
    done
    for path in /sys/class/android_usb/android0/state /sys/class/udc/*/state; do
        [ -r "$path" ] || continue
        state=$(cat "$path" 2>/dev/null)
        case "$state" in
            CONNECTED|CONFIGURED|connected|configured|powered|addressed|suspended) return 0 ;;
        esac
    done
    return 1
}

gadget_data_seen() {
    for path in /sys/class/android_usb/android0/state /sys/class/udc/*/state; do
        [ -r "$path" ] || continue
        state=$(cat "$path" 2>/dev/null)
        case "$state" in
            CONNECTED|CONFIGURED|connected|configured|powered|addressed|suspended) return 0 ;;
        esac
    done
    return 1
}

is_data_peer() {
    role=$(usb_role)
    case "$role" in
        host|source|*\[host\]*|*\[source\]*)
            USBMANAGER_SKIP_REASON=otg
            return 1
            ;;
    esac
    type=$(usb_type)
    case "$type" in
        DCP|USB_DCP|HVDCP|HVDCP_3|HVDCP_3P5|APPLE_BRICK_ID|Wireless)
            USBMANAGER_SKIP_REASON=charge_only
            return 1
            ;;
        SDP|USB_SDP|CDP|USB_CDP) ;;
        *)
            gadget_data_seen || {
                USBMANAGER_SKIP_REASON=charge_only
                return 1
            }
            ;;
    esac
    usb_present || { USBMANAGER_SKIP_REASON=not_present; return 1; }
    USBMANAGER_SKIP_REASON=
    return 0
}

is_device_locked() {
    power_state=$(dumpsys power 2>/dev/null)
    printf '%s' "$power_state" | grep -q -E 'mWakefulness=(Asleep|Dozing)|mInteractive=false' && return 0

    trust_state=$(dumpsys trust 2>/dev/null)
    first_lock_state=$(printf '%s' "$trust_state" | sed -n 's/.*deviceLocked=//p' | head -n 1)
    case "$first_lock_state" in true|1) return 0 ;; esac

    window_policy=$(dumpsys window policy 2>/dev/null)
    printf '%s' "$window_policy" | grep -q -E \
        'isStatusBarKeyguard=true|mShowingLockscreen=true|mKeyguardShowing=true|keyguardShowing=true|showingLockscreen=true' && return 0
    return 1
}

apply_usb_config() {
    mode=$1
    adb=$2
    case "$mode" in
        none|mtp|rndis) ;;
        *) mode=none ;;
    esac
    [ "$adb" = "1" ] || adb=0

    if [ "$adb" = "1" ]; then
        settings put global adb_enabled 1
        setprop persist.sys.usb.config adb
        setprop ctl.start adbd
    else
        settings put global adb_enabled 0
        setprop persist.sys.usb.config none
        setprop ctl.stop adbd
    fi

    if [ "$mode" = "none" ]; then
        svc usb setFunctions >/dev/null 2>&1
    else
        svc usb setFunctions "$mode" >/dev/null 2>&1
    fi
    log_msg "Applied mode=$mode adb=$adb current=$(getprop sys.usb.config) adbd=$(getprop init.svc.adbd)"
}

launch_chooser() {
    token=$1
    am start --user current --activity-clear-top -n "$APP_COMPONENT" \
        --es usb_mode none \
        --ez adb_enabled false \
        --ei token "$token" >/dev/null 2>&1
    log_msg "Manual USB chooser launched token=$token"
}

dismiss_chooser() {
    [ -f "$SESSION_FILE" ] || return 0
    token=$(cat "$SESSION_FILE" 2>/dev/null)
    am broadcast --user current -a com.tiger.usbmanager.action.DISMISS_CHOOSER \
        --ei token "$token" >/dev/null 2>&1
    rm -f "$SESSION_FILE"
}
