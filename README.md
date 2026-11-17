# Dialer: calls and texts from a second phone over Bluetooth

**Version 2.4.4** (build 17) · 2026-11-17 · [Download the APK](../../releases/latest)

Use a phone **without a SIM** to make and answer calls and send texts through a phone **with a SIM**,
over Bluetooth. Leave the SIM phone in your bag or on the desk and use your main phone as the dialer.

The same APK goes on both phones, and you pick each phone's role on first launch:

- **SIM phone:** holds the SIM cards and places the calls and texts. Developed on a Redmi Note 13 Pro.
- **Controller:** the phone you hold. It has a One UI–style dialer, call screen and messages app.
  Developed on a Samsung Galaxy S25 Ultra.

Both phones need Android 12 or newer. It was developed and tested on Android 16.

> **Voice stays on the SIM phone.** Android doesn't let apps pass call audio to another phone. To talk
> hands-free, connect Bluetooth earbuds to the SIM phone (see step 5 of the setup).

## Features

### Calls
- Dial from the controller's keypad, Recents or Contacts, and the SIM phone places the call.
- Dual SIM: choose which SIM to call from, or use the SIM phone's default.
- Incoming calls ring on the controller with **Answer**, **Decline** and **Reply with message**.
- **Silence** a ringing call without declining it, with a button or either volume key.
- Reply with a ready-made text, such as "Can't talk now", or write your own.
- Missed-call notifications with **Call back** and **Message** buttons.

### In-call screen
- Call timer that starts when the other person answers.
- Shows which SIM the call is on.
- Mute, Speaker and Bluetooth audio buttons.
- Keypad tones for phone menus ("press 1 for…").
- Hold and Resume, Add call, Merge into a conference, and Swap between two calls.

### Dialer
- Keypad, Recents and Contacts tabs, styled like Samsung's One UI Phone app.
- Contact search as you type digits on the keypad (e.g. 7285 finds "Paul").
- Editable number with a cursor, plus paste.
- Recents: SIM phone call history with filters (all, missed, incoming, outgoing), search,
  swipe to call or message, and a message button on every row.
- Contacts: favourites, search, and swipe to call or message.
- Opens phone links and the **Call** button in other apps.

### Messages
- Samsung-style Messages app: conversation list, new conversation, and chat view.
- Send from the SIM of your choice. Failed texts can be resent.
- One-tap **Copy** for verification codes (OTP), in the notification and in the chat.
- Search, mark all as read, and delete conversations.
- Opens **Message** buttons and SMS links from other apps.

### SIM phone status
- SIM phone battery level and charging state, plus signal bars and carrier for each SIM, in Settings.
- Warns you when the SIM phone's battery drops to 15%.

### Auto-reply
- Optionally texts back automatically after a missed call or an incoming text, with your own message.
- Runs on the SIM phone, so it works even when the controller is out of range.
- Replies to the same number at most once every 30 minutes.

### Connection
- Reconnects by itself whenever the phones are in range (about 10 m), including after a reboot.
- Texts that arrive while the phones are apart are delivered once they reconnect.

## Install

1. Download the latest APK from [Releases](../../releases/latest) and copy it to **both** phones.
2. Open it on each phone and allow "Install unknown apps" when asked.
3. Play Protect may warn about an unknown developer. Tap **More details → Install anyway**.

## Setup (one time)

1. **Pair the phones over Bluetooth** in Android settings (Settings → Connections → Bluetooth).
2. **SIM phone:** open Dialer → **This phone has the SIM (SIM host)** → **Grant permissions** (allow all) →
   **Battery: allow running in background** → **Start relay**.
   - Xiaomi / Redmi / POCO: also tap **Xiaomi: turn on Autostart**, set Settings → Apps → Dialer →
     Battery saver to *No restrictions*, and lock the app in Recent apps.
   - Tap **Enable call control** and confirm. This adds keypad tones, hold, merge and swap, per-call
     mute, audio routing and the SIM label during calls.
3. **Controller:** open Dialer → **This phone is the controller** → allow Nearby devices, Notifications and
   Contacts → **Choose SIM phone** (pick your SIM phone) → **Battery: allow running in background**.
4. **Link the apps:** on the SIM phone tap **Allow new controller (2 min)**, then on the controller tap
   **Pair with SIM phone**. Both phones show a 6-digit code. If they match, tap **Codes match** on both.
5. **Connect your earbuds to the SIM phone.** Pair your Bluetooth earbuds (or headset) with the SIM phone,
   because that's where call audio plays. Earbuds with multipoint can stay connected to both phones at once.

That's it, you're good to go. Make and answer calls from the controller and talk through your earbuds.
From now on the controller reconnects to the SIM phone automatically.

## Limits

- Call audio plays only on the SIM phone. Use earbuds, the speaker, or the SIM phone itself.
- Bluetooth range only, roughly 10 m.
- Keypad tones, hold, merge and swap need **Enable call control** on the SIM phone.
- Picture messages (MMS) aren't supported, only plain SMS.

## Security

- Encrypted Bluetooth RFCOMM. Only phones paired in Bluetooth settings can connect at all.
- App-level pairing: an ECDH key exchange plus a 6-digit code confirmed on both screens, so a
  man-in-the-middle would show up as mismatched codes.
- The SIM phone accepts only its one paired controller, checked by Bluetooth address and key. Every command
  is signed (HMAC-SHA256) with a per-connection key and a sequence number, so commands can't be faked or replayed.
- The pairing key is stored encrypted with the Android Keystore.
- The SIM phone keeps an activity log of every call it placed.

## Building from source

You need JDK 17, the Android SDK (platform 36) and Gradle 8.11. Then run:

```bash
gradle assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Release builds are signed with a key in
`keystore/`, which is not included in this repository. Create your own (see `app/build.gradle.kts` for the
expected `keystore/keystore.properties`), and keep it safe: updates must be signed with the same key.

## Version history

- **2.4.4** (build 17, 2026-11-17): Silence button (and volume keys) to stop the ringing without declining, and a Message button on every Recents row.
- **2.4.3** (build 16, 2026-11-02): Answer and Decline buttons always show on the incoming call screen, with a call-style notification.
- **2.4.2** (build 15, 2026-10-28): Fixed the call timer for calls over 20 minutes (22:15 showed as 02:15).
- **2.4.1** (build 14, 2026-10-28): Fixed the other person sometimes not hearing you: the app no longer mutes the phone-wide microphone.
- **2.4** (build 13, 2026-10-27): Call timer starts when the other person answers. Battery and signal moved to Settings, and a Messages button was added.
- **2.3.2** (build 12, 2026-10-24): Fixed the bottom Keypad / Recents / Contacts bar not responding during a call.
- **2.3.1** (build 11, 2026-10-23): Removed the READ_SMS permission so the update installs over the old app again.
- **2.3** (build 10, 2026-10-23): Call control on the SIM phone: keypad tones during calls, hold, merge and swap. Also SIM phone battery and signal, OTP quick copy, old SMS import and auto-reply.
- **2.2** (build 9, 2026-10-16): Contact search while typing digits on the keypad, an editable number with a cursor, and Samsung-style Messages.
- **2.1** (build 8, 2026-10-15): Size and layout fixes so the new screens match the S25 Ultra.
- **2.0** (build 7, 2026-10-15): New One UI look matching the S25 Ultra Phone app, with Keypad, Recents and Contacts tabs. The app is now called Dialer.
- **1.5** (build 6, 2026-10-15): Call history, missed-call notifications, Mute and Speaker buttons, and quick-reply texts.
- **1.4** (build 5, 2026-10-15): SIM 2 is now the default for texts, with an option to switch.
- **1.3** (build 4, 2026-10-15): Hotspot switch is back, and texts can be sent from SIM 1 or SIM 2 on the controller.
- **1.2** (build 3, 2026-10-15): Removed the hotspot switch again; back to calls only.
- **1.1** (build 2, 2026-10-15): Added a switch to turn the SIM phone's hotspot on and off from the controller.
- **1.0** (build 1, 2026-10-15): First build: the controller dials through the SIM phone over secure Bluetooth, and incoming calls ring on the controller with Answer / Decline.
