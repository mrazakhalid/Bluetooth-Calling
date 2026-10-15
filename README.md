# BT Call Relay

**Version 1.3** (build 4) · 2026-10-15

Dial from a phone **without** a SIM (Samsung S25 Ultra) and have a phone **with** a SIM (Redmi Note 13 Pro)
place the call, over Bluetooth. The same APK goes on both phones; you pick the role on first launch.

**Voice stays on the SIM phone** (Android blocks apps from forwarding call audio). To talk without holding
the Redmi, use Bluetooth earbuds connected to both phones at once (multipoint).

## Install (both phones)

1. Copy `BTCallRelay.apk` to the phone (USB, Quick Share, Google Drive…) and open it.
2. Allow "Install unknown apps" for the app you opened it with, when asked.
3. Play Protect may warn about an unknown developer. Tap **More details → Install anyway**.

## One-time setup

1. **Bluetooth-pair the phones** in Android settings (Samsung: Settings → Connections → Bluetooth).
2. **Redmi:** open the app → *This phone has the SIM* → **Grant permissions** (allow all) →
   **Battery: allow running in background** → **Xiaomi: turn on Autostart** → **Start relay**.
   Also: Settings → Apps → BT Call Relay → Battery saver → *No restrictions*, and lock the app in Recent apps.
3. **Samsung:** open the app → *This phone is the controller* → allow Nearby devices + Notifications →
   **Choose SIM phone** (pick the Redmi) → **Battery: allow running in background**.
4. **Pair the apps:** on the Redmi tap **Allow new controller (2 min)**, then on the Samsung tap
   **Pair with SIM phone**. Both phones show a 6-digit code: if they match, tap **Codes match** on BOTH.

After that the Samsung reconnects by itself whenever the Redmi is in range (about 10 m), also after reboots.

## Using it

- Type a number or tap **Contacts**, then **Call**. The Redmi dials.
- **End call** hangs up. Incoming calls on the Redmi ring on the Samsung with **Answer / Decline**.
- Dual SIM: tap **SIM:** to pick which SIM the Redmi uses.
- To make "Call" in Samsung Contacts open this app, pick *BT Call Relay* when Android asks which app to use.

## Limits

- No voice on the Samsung (see above), and no keypad tones during a call (for "press 1" menus use the Redmi).
- Bluetooth range only, roughly 10 m.

## Security

- Secure (encrypted) Bluetooth RFCOMM; only Bluetooth-paired phones can connect at all.
- App-level pairing: ECDH key exchange plus a 6-digit code confirmed on both screens, so a man-in-the-middle is visible.
- The Redmi only accepts the one paired Samsung (address check + key). Every command is signed (HMAC-SHA256)
  with a per-connection key and a sequence number, so commands can't be faked or replayed.
- The pairing key is stored encrypted with the Android Keystore.
- The Redmi keeps an activity log of every call it placed and who asked.

## Rebuilding

Toolchain is in `C:\Work\Development\AndroidToolchain` (JDK 17, Android SDK 36, Gradle 8.11.1).

```bash
JAVA_HOME='C:\Work\Development\AndroidToolchain\jdk17' /c/Work/Development/AndroidToolchain/gradle-8.11.1/bin/gradle.bat assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`. Bump `versionCode` in `app/build.gradle.kts` for updates.

**Back up `keystore/`.** Updates must be signed with the same key, otherwise you have to uninstall first
(and pair again).

## Version history

- **1.3** (build 4, 2026-10-15): Hotspot switch is back, and texts can be sent from SIM 1 or SIM 2 on the Samsung.
- **1.2** (build 3, 2026-10-15): Removed the hotspot switch again; back to calls only.
- **1.1** (build 2, 2026-10-15): Added a switch to turn the Redmi hotspot on and off from the Samsung.
- **1.0** (build 1, 2026-10-15): First build: the Samsung dials through the Redmi over secure Bluetooth, and incoming calls ring on the Samsung with Answer / Decline.
