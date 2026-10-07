# USBManager — Android USB Management Module

[中文](README.md)

An Android system module based on the **LSPosed** framework. When the phone is connected to a computer with a cable, it displays a USB chooser that lets the user select the USB mode and ADB state for that connection.

### This module is 100% open source. The source code repository link can be found at the bottom of the page.

-----

<details>
<summary><h2>Experimental Feature (click to expand)</h2></summary>

Computer Recognition and Memory is disabled by default. The main app only provides scheme import, invocation and management. Device checks, USB interfaces, authentication algorithms and the matching Windows backend are supplied by the scheme.

1. Import a trusted author's scheme ZIP, reviewing its author, version and root execution notice.
2. Grant root access and run the scheme's device check. Its documentation determines whether a cable or Windows companion is needed.
3. After detection succeeds, enable recognition manually and use the matching Windows program. Open the first-time pairing window on the phone.
4. After pairing, edit the computer name, USB mode and ADB setting. Later connections apply the configuration returned by the scheme after authentication. Unknown computers, failures and timeouts retain the normal chooser flow.

Authors may write their own scheme and Windows backend. Only the app-to-scheme control contract is fixed; the phone-to-Windows protocol is the author's decision. The two former built-in schemes, their authentication runtime and detection code, and the original Windows companion now reside in the **[additional recognition project](https://github.com/TigerSpirit217/USBManagerRecognition)**. The main app contains no built-in recognition implementation.

Importing, updating, switching schemes or changing firmware requires detection and manual enabling again. Removal restores USB state, disables recognition and preserves computer records. The reference schemes migrate existing records. Importing a valid package does not establish hardware compatibility.

Root is unnecessary when this optional feature is unused; basic USB management is unaffected.

</details>

## Features

* **Automatic connection detection:** detects when the phone connects to a computer as a USB device.
* **Per-connection mode selection:** supports Charge only, File transfer (MTP), Photo transfer (PTP), USB tethering (RNDIS), and MIDI, with automatic landscape and portrait layouts.
* **One-tap ADB control:** selects whether USB debugging is enabled for the current connection.
* **No OTG prompts:** USB drives, keyboards, mice, and other peripherals connected while the phone acts as host remain under normal Android handling.
* **ADB off on unplug:** optionally turns USB debugging off when the cable is removed.
* **Lock-screen deferral:** waits until unlock by default, with an option to display the chooser while locked.
* **Game Do Not Disturb:** disabled by default; skips the chooser while a selected app is in the foreground and applies Charge only (recommended) or Use default configuration.

## Installation

### Requirements

* An Android device with an unlocked bootloader and root access.

* An **LSPosed**-compatible framework implementing modern Xposed API 101 or later.

* The APK requires Android 8.0 (API 26) or later. Module operation also depends on framework and system USB implementation compatibility.

### Steps

1. Download the latest APK from [Releases](https://github.com/TigerSpirit217/USBManager/releases).
2. Install the APK.
3. Enable **USBManager** in **LSPosed Manager → Modules**.
4. Add system (the Android framework) to the module scope.
5. Reboot the device.
6. Open USBManager and verify that the module status is healthy.

## Usage

1. Connect the phone to a computer with a USB cable.
2. Select a USB mode and the ADB state in the chooser.
3. Tap **OK** to apply.

The defaults are Charge only, USB debugging off, ADB off on unplug enabled, and chooser display while locked disabled. These options are configurable on the main page.

Game Do Not Disturb shows USB behavior and app selection only while enabled. Charge only (recommended) also disables USB debugging. Use default configuration applies the main page's default USB mode and debugging state, with a risk confirmation when selected. The app picker displays icons, names and package names. Its top-right search accepts app names and package names; the overflow menu can show system apps, hidden by default. Previously selected apps appear first when entering the picker; the order stays stable during selection.

## Building

    git clone https://github.com/TigerSpirit217/USBManager.git
    cd USBManager
    ./gradlew :app:assembleRelease

## Debugging

Search for USBManager on the Logs page in LSPosed Manager, or run:

    adb logcat -s USBManager

Main log markers:

* **[WATCHER]**: USB connection and chooser flow.
* **[AUTH]**: computer recognition.
* **[RX]**: system broadcasts.
* **[HOOK]**: module loading.
* **[CLIENT]**: communication between the app and system module.
* **[CONTROLLER]**: USB mode and ADB application.

## License

Copyright © TigerSpirit217 · Mulan PubL v2

This project is licensed under the Mulan Public License, version 2 (Mulan PubL v2). See [LICENSE](https://license.coscl.org.cn/MulanPubL-2.0) for the full license.

## Source and Releases

* Source: <https://github.com/TigerSpirit217/USBManager>
* Releases: <https://github.com/TigerSpirit217/USBManager/releases>
* Issues: <https://github.com/TigerSpirit217/USBManager/issues>
