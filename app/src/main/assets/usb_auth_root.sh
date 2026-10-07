#!/system/bin/sh
# Root-only USB Authenticate backend. Invoked only after explicit detection/enable.
set -eu
ACTION=${1:-}
APK=${2:-}
LIB=${3:-}
MODE=${4:-closed}
BACKEND=${5:-none}
PROFILE=${6:-none}
APP_PROCESS=${7:-/system/bin/app_process}
ROOT=/data/adb/usbmanager-auth
HOSTS=$ROOT/hosts
RUN=$ROOT/session
MOUNT_STAGE=/data/local/tmp/usbmanager-auth-mount
CONF=/vendor/etc/usb_compositions.conf
for CANDIDATE in /odm/etc/usb_compositions.conf /product/etc/usb_compositions.conf /vendor/etc/usb_compositions.conf; do
    [ -r "$CANDIDATE" ] && grep -q '^qxr,adb[[:space:]]' "$CANDIDATE" 2>/dev/null || continue
    CONF=$CANDIDATE
    break
done
QXR=/dev/usb-ffs/qxr
DAEMON_CLASS=com.tiger.usbmanager.auth.UsbAuthDaemon

# ReSukiSU can grant UID 0 while leaving the caller in the app mount namespace.
# Being able to list /data/adb does not prove that a bind mount will be visible
# to an init-managed HAL. Always re-enter its global mount namespace once when
# ksud is present; Magisk and ordinary KernelSU continue directly.
if [ "${USBMANAGER_GLOBAL:-0}" != 1 ] && [ -x /data/adb/ksud ] && [ "$ACTION" != list ] && [ "$ACTION" != delete ] && [ "$ACTION" != edit ]; then
    OUTER_ADB=$(settings get global adb_enabled 2>/dev/null || echo 0)
    OUTER_FUNCTIONS=$(svc usb getFunctions 2>/dev/null || echo none)
    if [ "$ACTION" = start ] && [ "$BACKEND" = nothing_qxr ]; then
        settings put global adb_enabled 1
        # Do not ask UsbDeviceManager to reconfigure an already working MTP
        # composition. Its delayed HAL request can otherwise overwrite D003.
        case ",$OUTER_FUNCTIONS," in
          *,mtp,*) ;;
          *) svc usb setFunctions mtp; sleep 1 ;;
        esac
    fi
    STATUS=0
    OUTPUT=$(printf 'USBMANAGER_GLOBAL=1 USBMANAGER_PARENT_ADB=%s sh %s %s %s %s %s %s %s %s\nexit\n' \
        "$OUTER_ADB" "$0" "$ACTION" "$APK" "$LIB" "$MODE" "$BACKEND" "$PROFILE" "$APP_PROCESS" | /data/adb/ksud debug su -g) || STATUS=$?
    printf '%s\n' "$OUTPUT"
    RESTORE_LINE=$(printf '%s\n' "$OUTPUT" | grep '^FRAMEWORK_RESTORE ' | tail -n 1 || true)
    if [ -n "$RESTORE_LINE" ]; then
        : # The global root session has already restored the framework and gadget.
    elif [ "$ACTION" = start ] && ! printf '%s\n' "$OUTPUT" | grep -q '^STARTED$'; then
        settings put global adb_enabled 0
        svc usb setFunctions "$OUTER_FUNCTIONS"
        settings put global adb_enabled "$OUTER_ADB"
        [ "$OUTER_ADB" != 1 ] || setprop ctl.start adbd
    fi
    case "$ACTION" in
      detect) printf '%s\n' "$OUTPUT" | grep -q '^BACKEND=' && exit 0 ;;
      start) printf '%s\n' "$OUTPUT" | grep -q '^STARTED$' && exit 0 ;;
      restore) printf '%s\n' "$OUTPUT" | grep -q '^FRAMEWORK_RESTORE ' && exit 0 ;;
      list) exit 0 ;;
      delete) printf '%s\n' "$OUTPUT" | grep -q '^DELETED$' && exit 0 ;;
    esac
    exit "$STATUS"
fi

mkdir -p "$ROOT" "$HOSTS"
chmod 700 "$ROOT" "$HOSTS"

trace() { echo "$(date +%s%3N) $*" >> "$ROOT/last-session.log"; }

daemon_start() {
    MOUNT=$1
    PAIR=$2
    LOG=$3
    RESULT=$4
    rm -f "$RESULT" "$RESULT.tmp"
    CLASSPATH="$APK" "$APP_PROCESS" /system/bin "$DAEMON_CLASS" "$LIB" "$MOUNT" "$HOSTS" "$PAIR" "$RESULT" "$PROFILE" > "$LOG" 2>&1 &
    DAEMON=$!
    # app_process normally becomes ready in well under a second. Polling once per
    # second added a full second to every cable insertion on the common path.
    for i in $(seq 1 30); do grep -q '^READY ' "$LOG" && return 0; sleep 0.1; done
    return 1
}

standard_composition_ready() {
    EXPECTED_DATA=$1
    EXPECTED_ADB=$2
    CURRENT_LINKS=$(ls -l /config/usb_gadget/g1/configs/b.1 2>/dev/null || true)
    echo "$CURRENT_LINKS" | grep -q ffs.qxr && return 1
    for FUNCTION in mtp ptp rndis midi accessory audio_source ncm; do
        case ",$EXPECTED_DATA," in
          *,$FUNCTION,*) echo "$CURRENT_LINKS" | grep -q "\.$FUNCTION" || return 1 ;;
          *) echo "$CURRENT_LINKS" | grep -q "\.$FUNCTION" && return 1 ;;
        esac
    done
    if [ "$EXPECTED_ADB" = 1 ]; then
        echo "$CURRENT_LINKS" | grep -q ffs.adb || return 1
    else
        echo "$CURRENT_LINKS" | grep -q ffs.adb && return 1
    fi
    return 0
}

wait_standard_composition() {
    EXPECTED_DATA=$1
    EXPECTED_ADB=$2
    LIMIT=$3
    REQUIRED_STABLE=${4:-3}
    STABLE=0
    for i in $(seq 1 "$LIMIT"); do
        if standard_composition_ready "$EXPECTED_DATA" "$EXPECTED_ADB"; then
            STABLE=$((STABLE + 1))
            [ "$STABLE" -lt "$REQUIRED_STABLE" ] || return 0
        else
            STABLE=0
        fi
        sleep 0.1
    done
    return 1
}

common_capability() {
    [ "$(id -u)" = 0 ] || return 1
    [ -r "$APK" ] && [ -r "$LIB" ] || return 1
    [ -d /config/usb_gadget ] || return 1
    grep -qw functionfs /proc/filesystems || return 1
    [ -n "$(ls /sys/class/udc 2>/dev/null)" ] || return 1
}

generic_prepare() {
    # Vendor-managed Qualcomm gadgets are deliberately excluded: a virtual-only
    # ConfigFS pass would over-report support on devices whose physical UDC rejects
    # direct gadget takeover (as observed on Nothing A065).
    if [ -e "$CONF" ] && grep -q '^qxr,adb[[:space:]]' "$CONF" 2>/dev/null; then
        return 1
    fi
    G=/config/usb_gadget/g1
    [ -d "$G/functions" ] && [ -d "$G/configs" ] || return 1
    C=$(find "$G/configs" -mindepth 1 -maxdepth 1 -type d | head -n 1)
    [ -n "$C" ] || return 1
    UDC=$(cat "$G/UDC" 2>/dev/null || true)
    [ -n "$UDC" ] || return 1
    [ -d "/sys/class/udc/$UDC" ] || return 1
    F=/dev/usb-ffs/usb_auth
    FN=$G/functions/ffs.usb_auth
    LINK=$C/usbmanager_auth
    [ ! -e "$FN" ] && [ ! -L "$LINK" ] || return 1
    mkdir -p "$F" || return 1
    mountpoint -q "$F" || mount -t functionfs usb_auth "$F" || return 1
    daemon_start "$F" "$MODE" "$RUN/daemon.log" "$RUN/auth-result" || return 1
    echo "$DAEMON" > "$RUN/daemon.pid"
    echo "$UDC" > "$RUN/generic.udc"
    echo "$LINK" > "$RUN/generic.link"
    touch "$RUN/generic"
    echo '' > "$G/UDC"
    mkdir "$FN"
    ln -s "$FN" "$LINK"
    echo "$UDC" > "$G/UDC"
    for i in $(seq 1 30); do
        [ "$(cat "$G/UDC" 2>/dev/null || true)" = "$UDC" ] && return 0
        sleep 0.1
    done
    return 1
}

generic_probe() {
    # Classify Qualcomm/QXR gadgets before creating state or touching the active
    # USB configuration. Their supported path is the read-only Nothing probe.
    if [ -e "$CONF" ] && grep -q '^qxr,adb[[:space:]]' "$CONF" 2>/dev/null; then
        return 1
    fi
    rm -rf "$RUN"; save_state || return 1
    trap 'restore; release_operation' EXIT
    RESULT=0
    generic_prepare || RESULT=$?
    restore
    trap - EXIT
    return "$RESULT"
}

nothing_probe() {
    [ "$(getprop init.svc.vendor.usbgadget-hal-1-2)" = running ] || return 1
    grep -q '^qxr,adb[[:space:]]' "$CONF" || return 1
    [ -d /config/usb_gadget/g1 ] || return 1
    ls /sys/class/udc | grep -qv '^dummy_udc' || return 1
    [ -e "$QXR/ep0" ] && [ ! -e "$QXR/ep1" ] || return 1
    daemon_start "$QXR" closed "$ROOT/nothing-probe.log" "$ROOT/nothing-probe-result" || return 1
    kill "$DAEMON" 2>/dev/null || true
    for i in $(seq 1 50); do [ ! -e "$QXR/ep1" ] && return 0; sleep 0.1; done
    return 1
}

save_state() {
    mkdir "$RUN" || return 1
    getprop vendor.usb.config > "$RUN/vendor-config"
    getprop persist.vendor.usb.config.extra > "$RUN/extra"
    LINKS=$(ls -l /config/usb_gadget/g1/configs/b.1 2>/dev/null || true)
    if [ -n "${USBMANAGER_PARENT_ADB:-}" ]; then
        echo "$USBMANAGER_PARENT_ADB" > "$RUN/adb"
    elif echo "$LINKS" | grep -q ffs.adb; then echo 1 > "$RUN/adb"; else echo 0 > "$RUN/adb"; fi
    COMPOSITION=''
    for NAME in mtp ptp rndis midi accessory audio_source ncm qxr adb; do
        echo "$LINKS" | grep -q "\.$NAME" || continue
        if [ -n "$COMPOSITION" ]; then COMPOSITION="$COMPOSITION,$NAME"; else COMPOSITION=$NAME; fi
    done
    [ -n "$COMPOSITION" ] || COMPOSITION=none
    echo "$COMPOSITION" > "$RUN/functions"
}

nothing_prepare() {
    trace "nothing_prepare begin mode=$MODE conf=$CONF shell_mnt=$(readlink /proc/self/ns/mnt 2>/dev/null || echo unknown) init_mnt=$(readlink /proc/1/ns/mnt 2>/dev/null || echo unknown)"
    grep -q '^qxr,adb[[:space:]]' "$CONF" || return 1
    [ -e "$QXR/ep0" ] && [ ! -e "$QXR/ep1" ] || return 1
    rm -rf "$MOUNT_STAGE"
    mkdir "$MOUNT_STAGE"
    chmod 700 "$MOUNT_STAGE"
    cp "$CONF" "$MOUNT_STAGE/patched.conf"
    echo 'mtp,qxr,adb  0x05C6  0xD003' >> "$MOUNT_STAGE/patched.conf"
    chmod 600 "$MOUNT_STAGE/patched.conf"
    chcon u:object_r:vendor_configs_file:s0 "$MOUNT_STAGE/patched.conf" 2>/dev/null || true
    mount --bind "$MOUNT_STAGE/patched.conf" "$CONF"
    trace "composition table mounted"
    for i in $(seq 1 80); do [ -e /dev/usb-ffs/mtp/ep1 ] && break; sleep 0.1; done
    [ -e /dev/usb-ffs/mtp/ep1 ] || return 1
    trace "mtp endpoints ready"
    daemon_start "$QXR" "$MODE" "$RUN/daemon.log" "$RUN/auth-result" || return 1
    trace "auth daemon ready pid=$DAEMON"
    echo "$DAEMON" > "$RUN/daemon.pid"
    OLD_HAL_PID=$(pidof android.hardware.usb.gadget@1.2-service-qti 2>/dev/null || true)
    setprop ctl.restart vendor.usbgadget-hal-1-2
    HAL_PID=''
    for i in $(seq 1 100); do
        HAL_PID=$(pidof android.hardware.usb.gadget@1.2-service-qti 2>/dev/null || true)
        [ "$(getprop init.svc.vendor.usbgadget-hal-1-2)" = running ] && [ -n "$HAL_PID" ] && [ "$HAL_PID" != "$OLD_HAL_PID" ] && break
        sleep 0.1
    done
    [ -n "$HAL_PID" ] && [ "$HAL_PID" != "$OLD_HAL_PID" ] || return 1
    # UsbDeviceManager reapplies MTP+ADB after the restarted HAL reconnects. Wait
    # for the actual ConfigFS links to settle instead of sleeping six seconds.
    wait_standard_composition mtp 1 50 10 || return 1
    HAL_ROWS=0
    [ -z "$HAL_PID" ] || HAL_ROWS=$(grep -c '^mtp,qxr,adb[[:space:]]' "/proc/$HAL_PID/root$CONF" 2>/dev/null || echo 0)
    HAL_MNT=unknown
    [ -z "$HAL_PID" ] || HAL_MNT=$(readlink "/proc/$HAL_PID/ns/mnt" 2>/dev/null || echo unknown)
    trace "gadget HAL ready pid=$HAL_PID patched_rows=$HAL_ROWS hal_mnt=$HAL_MNT"
    [ "$HAL_ROWS" -gt 0 ] || return 1

    # QTI consults vendor.usb.config only for its ADB-only HIDL branch. A blank
    # data-function request with ADB enabled selects that branch; asking for MTP
    # directly would ignore the vendor composition and silently drop QXR.
    settings put global adb_enabled 1
    setprop vendor.usb.config mtp,qxr,adb
    svc usb setFunctions
    trace "vendor composition requested through ADB branch"
    READY=0
    for i in $(seq 1 100); do
        PID=$(cat /config/usb_gadget/g1/idProduct 2>/dev/null || true)
        LINKS=$(ls -l /config/usb_gadget/g1/configs/b.1 2>/dev/null || true)
        if [ "$PID" = 0xd003 ] && echo "$LINKS" | grep -q ffs.mtp && echo "$LINKS" | grep -q ffs.qxr && echo "$LINKS" | grep -q ffs.adb; then READY=1; break; fi
        sleep 0.1
    done
    trace "composition result ready=$READY pid=$PID"
    [ "$READY" = 1 ]
}

restore() {
    [ -d "$RUN" ] || return 0
    # A caller can be killed while restoring because the USB bus disappears.
    # A stale marker must never make all later recovery attempts no-ops.
    mkdir "$RUN/restoring" 2>/dev/null || true
    rm -f "$RUN/watchdog.armed"
    trace "restore begin"
    [ ! -f "$RUN/daemon.log" ] || cp "$RUN/daemon.log" "$ROOT/last-daemon.log"
    [ ! -f "$RUN/watchdog.log" ] || cp "$RUN/watchdog.log" "$ROOT/last-watchdog.log"
    [ ! -f "$RUN/daemon.pid" ] || kill "$(cat "$RUN/daemon.pid")" 2>/dev/null || true
    sleep 0.3
    if [ -f "$RUN/generic" ]; then
        G=/config/usb_gadget/g1
        UDC=$(cat "$RUN/generic.udc")
        LINK=$(cat "$RUN/generic.link")
        echo '' > "$G/UDC" 2>/dev/null || true
        rm -f "$LINK"
        rmdir "$G/functions/ffs.usb_auth" 2>/dev/null || true
        echo "$UDC" > "$G/UDC" 2>/dev/null || true
        umount /dev/usb-ffs/usb_auth 2>/dev/null || true
        rmdir /dev/usb-ffs/usb_auth 2>/dev/null || true
        rm -rf "$RUN"
        return 0
    fi
    SAVED_ADB=$(cat "$RUN/adb")
    SAVED_FUNCTIONS=$(cat "$RUN/functions")
    setprop persist.vendor.usb.config.extra "$(cat "$RUN/extra")"
    setprop vendor.usb.config none
    svc usb setFunctions >/dev/null 2>&1 || true
    # Wait until the temporary QXR link is actually gone before removing the
    # patched composition table. This is normally faster than the old fixed 1 s.
    for i in $(seq 1 30); do
        LINKS=$(ls -l /config/usb_gadget/g1/configs/b.1 2>/dev/null || true)
        echo "$LINKS" | grep -q ffs.qxr || break
        sleep 0.1
    done
    umount "$CONF" 2>/dev/null || true
    rm -rf "$MOUNT_STAGE"
    setprop vendor.usb.config "$(cat "$RUN/vendor-config")"
    DATA_FUNCTIONS=$(echo "$SAVED_FUNCTIONS" | sed 's/,adb//;s/adb,//;s/^adb$//;s/^none$//')
    settings put global adb_enabled "$SAVED_ADB"
    svc usb setFunctions "$DATA_FUNCTIONS" >/dev/null 2>&1 || true
    if [ "$SAVED_ADB" = 1 ]; then setprop ctl.start adbd; else setprop ctl.stop adbd; fi
    # Return as soon as the restored standard composition is stable. A fixed
    # four-second delay kept both unknown and known computers waiting after the
    # phone had already restored its USB functions.
    wait_standard_composition "$DATA_FUNCTIONS" "$SAVED_ADB" 50 || true
    echo "FRAMEWORK_RESTORE $SAVED_ADB $SAVED_FUNCTIONS"
    trace "restore physical complete adb=$SAVED_ADB functions=$SAVED_FUNCTIONS"
    rm -rf "$RUN"
}

release_operation() {
    rm -f "$ROOT/operation.lock/pid"
    rmdir "$ROOT/operation.lock" 2>/dev/null || true
}

case "$ACTION" in
  start|restore|edit|delete)
    if ! mkdir "$ROOT/operation.lock" 2>/dev/null; then
        OWNER=$(cat "$ROOT/operation.lock/pid" 2>/dev/null || true)
        case "$OWNER" in
          ''|*[!0-9]*) echo BUSY; exit 4 ;;
        esac
        if kill -0 "$OWNER" 2>/dev/null; then echo BUSY; exit 4; fi
        rm -f "$ROOT/operation.lock/pid"
        rmdir "$ROOT/operation.lock" 2>/dev/null || true
        mkdir "$ROOT/operation.lock" 2>/dev/null || { echo BUSY; exit 4; }
    fi
    echo $$ > "$ROOT/operation.lock/pid"
    trap 'release_operation' EXIT
    ;;
esac

case "$ACTION" in
  detect)
    set +e
    common_capability
    [ $? = 0 ] || { echo UNSUPPORTED; exit 3; }
    generic_probe
    [ $? != 0 ] || { echo BACKEND=generic_configfs; exit 0; }
    nothing_probe
    [ $? != 0 ] || { echo BACKEND=nothing_qxr; exit 0; }
    echo UNSUPPORTED; exit 3
    ;;
  start)
    [ "$MODE" = closed ] || [ "$MODE" = pair ]
    # A closed recognition session may already be active for this cable. Pairing
    # replaces it atomically by restoring the saved gadget state first.
    [ ! -d "$RUN" ] || restore
    rm -rf "$RUN"; save_state
    : > "$ROOT/last-session.log"
    trace "start requested mode=$MODE backend=$BACKEND saved_adb=$(cat "$RUN/adb") saved_functions=$(cat "$RUN/functions")"
    trap 'restore; release_operation' EXIT
    touch "$RUN/watchdog.armed"
    nohup sh -c 'sleep 120; R=/data/adb/usbmanager-auth/session; [ -e "$R/watchdog.armed" ] && sh "$R/script" restore "$(cat "$R/apk")" "$(cat "$R/lib")"' > "$RUN/watchdog.log" 2>&1 < /dev/null &
    cp "$0" "$RUN/script"; echo "$APK" > "$RUN/apk"; echo "$LIB" > "$RUN/lib"
    if [ "$BACKEND" = generic_configfs ]; then
        generic_prepare
    elif [ "$BACKEND" = nothing_qxr ]; then
        nothing_prepare
    else
        echo 'unsupported saved backend' >&2
        exit 3
    fi
    echo STARTED
    AUTH_RESULT=TIMEOUT
    ATTEMPTS=20
    [ "$MODE" != pair ] || ATTEMPTS=120
    for i in $(seq 1 "$ATTEMPTS"); do
        if [ -s "$RUN/auth-result" ]; then
            AUTH_RESULT=$(cat "$RUN/auth-result")
            if [ "$MODE" = pair ]; then
                case "$AUTH_RESULT" in
                  PAIRED\|*|KNOWN\|*) ;;
                  *) AUTH_RESULT=TIMEOUT; sleep 0.5; continue ;;
                esac
            fi
            # Give nativeSend time to deliver the encrypted response before teardown.
            sleep 0.5
            break
        fi
        sleep 0.5
    done
    restore
    trap 'release_operation' EXIT
    # Pairing also applies its durable profile before reporting completion.
    case "$AUTH_RESULT" in
      PAIRED\|*|KNOWN\|*)
        if [ "$MODE" = pair ]; then
            APPLY_MODE=$(printf '%s' "$AUTH_RESULT" | cut -d '|' -f 4)
            APPLY_ADB=$(printf '%s' "$AUTH_RESULT" | cut -d '|' -f 5)
            case "$APPLY_MODE:$APPLY_ADB" in
              none:true|none:false|mtp:true|mtp:false|ptp:true|ptp:false|rndis:true|rndis:false|midi:true|midi:false)
                if [ "$APPLY_ADB" = true ]; then settings put global adb_enabled 1; else settings put global adb_enabled 0; fi
                [ "$APPLY_MODE" != none ] || APPLY_MODE=''
                svc usb setFunctions "$APPLY_MODE"
                ;;
            esac
        fi
        ;;
    esac
    printf 'AUTH_RESULT %s\n' "$AUTH_RESULT"
    ;;
  restore)
    restore
    ;;
  list)
    CLASSPATH="$APK" "$APP_PROCESS" /system/bin "$DAEMON_CLASS" list "$HOSTS"
    ;;
  edit)
    CLASSPATH="$APK" "$APP_PROCESS" /system/bin "$DAEMON_CLASS" edit "$HOSTS" "$MODE" "$PROFILE"
    ;;
  delete)
    ID=$MODE
    echo "$ID" | grep -qE '^[0-9a-f]{64}$'
    rm -f "$HOSTS/$ID.properties" "$HOSTS/$ID.entry"
    echo DELETED
    ;;
  *)
    echo 'usage: usb_auth_root.sh detect|start|restore|list|edit|delete' >&2
    exit 2
    ;;
esac
