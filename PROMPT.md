# PROMPT.md — install Sfera on a TV

You are an AI coding agent (Claude Code, Codex, Gemini CLI, Cursor or similar) on the person's computer.
They asked you to put **Sfera**, a voice assistant for Android TV, on their TV. Follow this procedure on
the real TV, in order. Talk to the person in their language; keep it short and plain.

## Rules that are not negotiable

- **Never see or type the person's API keys.** They enter the DeepSeek key (and the optional Tavily key)
  themselves, on the TV, in Sfera's settings. Do not ask them to paste a key into the chat, do not put one
  in a command, a file or `adb shell input text`.
- **Change only what this procedure changes:** install the app, grant it the microphone and "display over
  other apps", make it the assistant. Do not disable, uninstall or reconfigure anything else. Turning off
  Google Assistant (step 6) is the person's choice: ask first.
- **Close ADB at the end** (step 9). Port 5555 is unencrypted; leaving it open lets anyone on the network
  control the TV.
- **Stop and say so** when the TV is not Android TV / Google TV (no ADB at all, Tizen, webOS, Roku, VIDAA),
  when its Android is older than 8.0 (SDK below 26), or when the install fails twice. Do not retry in a
  loop and do not lower `minSdk`.

## 0. What you need

- `adb` (Android platform-tools) on this computer. If missing: on Windows `winget install
  Google.PlatformTools`, on macOS `brew install android-platform-tools`, on Linux the distro's
  `android-tools-adb` / `android-sdk-platform-tools` package. Ask before installing anything.
- The TV and this computer on the same network.
- The APK: download `sfera-<version>.apk` from the latest release at
  https://github.com/Kykylllka01/tv-voice-assistant/releases, or build it from this repository
  (`./gradlew assembleRelease`, needs JDK 17 and the Android SDK; the APK lands in
  `app/build/outputs/apk/release/`).

## 1. Turn on network debugging (the person does this)

Tell the person, for Google TV / Android TV 12+:

1. Settings → System → About → click **Android TV OS build** 7 times ("You are now a developer").
2. Settings → System → Developer options → turn on **USB debugging** and **Network debugging** /
   **Wireless debugging** (the name differs by brand).
3. Settings → Network → the Wi-Fi or Ethernet connection → note the **IP address**.

## 2. Connect

```bash
adb connect <tv-ip>:5555
adb devices
```

The TV shows "Allow USB debugging?": the person selects **Always allow from this computer** and OK.
`adb devices` must list the TV as `device` (not `unauthorized`). Check the system:

```bash
adb shell getprop ro.build.version.sdk          # 26 or more
adb shell pm list features | grep -c leanback    # 1 on Android TV / Google TV
```

## 3. Install and grant

```bash
adb install -r sfera.apk
adb shell pm grant com.tvvoice.assistant android.permission.RECORD_AUDIO
adb shell appops set com.tvvoice.assistant SYSTEM_ALERT_WINDOW allow
```

The last line lets a reminder open the panel over whatever is on screen; Android blocks it otherwise.

## 4. Make Sfera the assistant

The remote's voice button starts whichever app holds the assistant role.

```bash
adb shell cmd role add-role-holder android.app.role.ASSISTANT com.tvvoice.assistant 0
adb shell cmd role get-role-holders android.app.role.ASSISTANT   # must print com.tvvoice.assistant
```

## 5. Keys (the person does this)

Tell the person to open **Sfera** from the TV's app list and:

- paste or type a **DeepSeek API key** (https://platform.deepseek.com → API keys; the account needs a small
  balance), then **Save**;
- optionally a **Tavily key** for web search (https://tavily.com, free tier);
- press **Задать проверочный вопрос** / **Ask a test question**: an answer means the key works, and a line
  under it says whether search works.

If the test fails, read the reason on screen; for more, `adb logcat -d -s TvVoice` shows the HTTP status
(keys are never logged).

## 6. Google Assistant (ask first)

With Google Assistant enabled, its hotword detector competes for the microphone and recognition gets flaky.
Ask the person whether to turn it off; only on a yes:

```bash
adb shell pm disable-user --user 0 com.google.android.katniss
```

Undo at any time with `adb shell pm enable com.google.android.katniss`.

## 7. Try it with the person

Tell them:

- **Hold** the remote's microphone button while speaking, release when done. The panel appears over any
  screen, HDMI included.
- **Next question:** press an arrow (◀ ▶) first, then hold the microphone again. The remote stays "open"
  after a voice session until a real key is pressed; this is how the hardware works, not a bug.
- **OK** stops the reading; on an answer with sources it shows them with a QR code, and OK again opens one in
  the TV's browser. **Back** cancels or closes.

Things to try: "Какая погода на выходных?", "Открой YouTube", "Сделай тише", "Напомни через 5 минут
проверить чайник", "Запомни, я живу в Казани".

## 8. If something is wrong

| Symptom | Cause and fix |
|---|---|
| Nothing happens on the voice button | `cmd role get-role-holders android.app.role.ASSISTANT` is not Sfera: repeat step 4. |
| "No microphone permission" | Repeat the `pm grant` in step 3. |
| Every second press does nothing | Press an arrow before the next question (step 7). |
| "Search is unavailable" while DeepSeek works | Tavily refuses some networks (it was seen refusing Russian IPs). Route `api.tavily.com` through a VPN on the router, or leave search off. |
| A reminder fired but no panel | Repeat the `appops set ... SYSTEM_ALERT_WINDOW allow` in step 3. |
| Recognition is poor | Step 6. |

Domains Sfera talks to: `api.deepseek.com` (or the server set in settings), `api.tavily.com`,
`api.open-meteo.com`, `geocoding-api.open-meteo.com`, `www.google.com` (site icons). Speech recognition and
voices are the TV's own Google services.

## 9. Close ADB

Tell the person to turn **Network debugging** and **USB debugging** off in Developer options (and, if the
menu has it, **Revoke USB debugging authorisations**). Then check from this computer that the port is
closed:

```bash
adb disconnect
python3 -c "import socket,sys; s=socket.socket(); s.settimeout(3); print('OPEN, ask again' if s.connect_ex((sys.argv[1],5555))==0 else 'closed')" <tv-ip>
```

Only when it prints `closed` is the job done. Sfera keeps working without ADB.
