# USBManager — Android USB management module

An **LSPosed**-based Android system module that shows a USB chooser every time you plug into a computer, so you can decide on the spot which USB mode to use and whether to enable ADB.

> The "device recognition + memory" feature has been removed. The ADB device side cannot reliably read the host computer's PID/VID, so different computers could be misidentified as the same one and incorrectly get another computer's configuration. This module no longer remembers any computer; it **prompts on every connection** instead.

## Features

* **Automatic connection detection**: hooks `UsbDeviceManager` (with the system `USB_STATE` broadcast as a fallback path) to detect when the phone connects to a computer as a USB device

* **Prompt on every connection**: shows the USB chooser every time, so you pick the mode and ADB state for this session — no memory, no auto-apply

* **Multiple modes**: Charge only / File transfer (MTP) / Photo transfer (PTP) / USB tethering (RNDIS) / MIDI

* **One-tap ADB**: decide in the chooser whether to enable USB debugging this time

* **No chooser for OTG**: the phone acting as a USB host (OTG drives, keyboards, mice, etc.) does not prompt; it is recognized natively by the system and needs no ADB

* **ADB off on unplug**: automatically turns ADB off after unplugging (can be disabled), ensuring adbd stops

* **Root fallback**: when framework APIs are unavailable, automatically tries `su` to write system properties (requires root)

## Installation

### Prerequisites

* A rooted Android device with an unlocked bootloader

* **LSPosed** installed

* Android 12+ (recommended) or 11

### Steps

1. Download the latest APK from [Releases](../../releases)
2. Install the APK on your device
3. Open **LSPosed Manager** → Modules → enable **USBManager**
4. **Scope**: tick `system` (system framework)
5. Reboot the device
6. Launch the USBManager app from the home screen to read the guide and module settings

## Usage

1. Plug a USB cable into a computer
2. The USB chooser appears every time (via a full-screen high-priority notification when locked / background-restricted)
3. Choose the USB mode (Charge only / File transfer / Photo transfer / Tethering / MIDI) and the ADB toggle
4. Tap "OK" to apply

## Defaults

* Default USB mode: **Charge only**

* Default: enable USB debugging: **off**

* Turn ADB off on unplug: **on**

* Show chooser while locked: **off** (deferred until unlock by default)

These defaults can be changed from the USBManager app.

> **Upgrade note**: after upgrading from an older version, the first launch clears previously saved device-recognition data (`usbmanager_hosts` and the system_server-side fallback copy) so leftover data cannot affect the new behavior.

## Building

```bash
# Clone the repository
git clone https://github.com/your-username/USBManager.git
cd USBManager

# Build with Gradle
./gradlew :app:assembleRelease
```

## Debugging

### Viewing logs

Module logs are written to the `USBManager` tag. View them from LSPosed Manager:

1. Open **LSPosed Manager**
2. Tap **Logs** → search for **USBManager**
3. Review the relevant logs

Or use ADB logcat:

```bash
adb logcat -s USBManager
```

Key log tags:

* `[WATCHER]` — USB event pipeline

* `[RX]` — broadcast receiver

* `[HOOK]` — hook initialization

* `[CLIENT]` — ContentProvider communication

* `[CONTROLLER]` — USB mode / ADB configuration application

## License

This project is licensed under the Mulan Public License, version 2 (Mulan PubL v2).
See [LICENSE](https://license.coscl.org.cn/MulanPubL-2.0) for the full text.

## Source & Releases

* Source repository: <https://github.com/TigerSpirit217/USBManager>

* Releases: <https://github.com/TigerSpirit217/USBManager/releases>

* Issues: <https://github.com/TigerSpirit217/USBManager/issues>