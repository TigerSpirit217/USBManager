# USBManager — Android USB Management Module

[中文](README.md)

An Android system module based on the **LSPosed** framework. When the phone is connected to a computer with a cable, it displays a USB chooser that lets the user select the USB mode and ADB state for that connection.

### This module is 100% open source. The source code repository link can be found at the bottom of the page.

-----

<details>
<summary><h2>Experimental Feature (click to expand)</h2></summary>

This feature allows the phone to recognize and remember trusted computers. It is disabled by default. While disabled, the USB chooser continues to appear whenever the phone connects to a computer.

* Before first use, run the local capability check:
>1. Grant this app root access in your root manager.
>2. Reopen the app, enter **Computer Recognition and Memory**, and tap Detect. Detection runs entirely on the phone and does not require a cable or computer.
>3. If the app reports that computer recognition and memory are supported, its built-in method can mount the interface required by this feature on your device, and you may continue.

* Save a computer:
>1. Connect the phone to the target Windows computer with a USB cable, then apply the desired mode and ADB state in the USB chooser.
>2. Confirm that the Windows backend is running. Search for the USBManagerWinBackEnd process in Task Manager.
>3. Open **Computer Recognition and Memory** in the phone app and tap **Allow one new computer**. Pairing starts immediately; keep the cable connected.
>4. After pairing succeeds, a name and configuration dialog appears. You may accept or edit its values.

* Connect to a computer:
>1. With the Windows backend running, connect the phone to a saved computer by USB. The phone verifies its identity and automatically applies the saved configuration.
>2. If the Windows backend is not running, the computer has not been saved, or authentication fails, the normal USB chooser appears.

### Important Notice

The primary compatibility targets are AOSP, Google Android, and near-stock systems. Device manufacturers may alter or restrict system USB behavior. The in-app detection result is for reference and does not guarantee stable operation.

This method does not modify the phone kernel.

If you do not need this experimental feature, you can leave root access disabled without affecting the basic features.

The authentication interface is created only when needed and removed immediately afterward. Preparing and enabling it takes time. When this feature is enabled, the delay between connecting the cable and showing the chooser or applying a saved configuration increases significantly and may reach 30 seconds. Disable the experimental feature if this delay is unacceptable.

This experimental feature requires the **[USBManagerWinBackEnd](https://github.com/TigerSpirit217/USBManagerWinBackEnd)** Windows backend. Its program, documentation, and releases are available in that project.

-----

</details>

## Features

* **Automatic connection detection:** detects when the phone connects to a computer as a USB device.
* **Per-connection mode selection:** supports Charge only, File transfer (MTP), Photo transfer (PTP), USB tethering (RNDIS), and MIDI.
* **One-tap ADB control:** selects whether USB debugging is enabled for the current connection.
* **No OTG prompts:** USB drives, keyboards, mice, and other peripherals connected while the phone acts as host remain under normal Android handling.
* **ADB off on unplug:** optionally turns USB debugging off when the cable is removed.
* **Lock-screen deferral:** waits until unlock by default, with an option to display the chooser while locked.

## Installation

### Requirements

* An Android device with an unlocked bootloader and root access.

* The **LSPosed** framework.

* Android 11 or later; Android 12+ is recommended.

### Steps

1. Download the latest APK from [Releases](../../releases).
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
