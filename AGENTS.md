# AGENTS.md

Instructions for coding agents (Claude Code, Codex, Gemini CLI, Cursor, Copilot and others) working in this
repository. People should start with [README.md](README.md); this file is the same project in the form an
agent needs.

## First: which job are you doing?

| You were asked to | Do this |
|---|---|
| Put Sfera on a TV: install, set up, make the voice button work | Read [PROMPT.md](PROMPT.md) and follow it on the real TV. Do not change code for that. |
| Change the app: a feature, a fix, a translation, the look | Read on. |

## What Sfera is

A voice assistant for Android TV / Google TV. The remote's voice button starts `AssistActivity` because the app
holds the `ASSISTANT` role ([docs/FINDINGS.md](docs/FINDINGS.md) explains how that was found). The overlay
listens while the button is held (`SpeechRecognizer`), sends the question to an OpenAI-compatible
`/chat/completions` endpoint (DeepSeek by default) with tool calling, streams the answer as it types and reads
it aloud (`TextToSpeech`). Kotlin, no AndroidX, coroutines are the only dependency.

## Repo map

| Path | What |
|---|---|
| `app/src/main/java/com/tvvoice/assistant/AssistActivity.kt` | The overlay: stages, hints, remote keys, sources with QR, weather card, reminders. |
| `.../SettingsActivity.kt` | The app tile: keys, memory facts, voice, model and server, test question. |
| `.../chat/` | `ChatClient` (HTTP + SSE), `Sse`, `Conversation` (history, tool steps), `Answerer` (tool loop), `Tools` (definitions). |
| `.../search/WebSearch.kt`, `.../weather/Weather.kt` | Tavily search, Open-Meteo weather. |
| `.../control/TvControl.kt`, `.../reminders/Reminders.kt` | Apps, YouTube, volume; reminders (AlarmManager, receiver, boot). |
| `.../settings/` | `Prefs`, `SecureStore` (Keystore AES-GCM), `Memory` (facts, recent dialogue). |
| `.../ui/`, `.../voice/`, `.../text/` | Custom views (orb, wave, typewriter, weather card, QR), speech in and out, sentence splitting. |
| `app/src/main/res/values*/strings.xml` | English and Russian strings, including the system prompt. |
| `app/src/test/` | JVM unit tests; `test/resources/qr/` holds reference QR matrices. |
| `tools/make_icons.py` | Draws the launcher banner and icons. |
| `install.sh`, [PROMPT.md](PROMPT.md) | Install on a TV, by hand or by an agent. |

## Build and test

```bash
./gradlew testDebugUnitTest      # must stay green
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew assembleRelease        # signed with keystore.properties if present, else the debug key
```

JDK 17 and the Android SDK (compileSdk 34, minSdk 26). On the TV, a debug build takes a typed question for
checks without a voice: `adb shell am start -n com.tvvoice.assistant/.AssistActivity --es question "..."`,
add `--ez quiet true` to keep it silent. Release builds ignore both.

## Rules

- **Never commit secrets or personal data.** API keys, `keystore.properties`, `*.jks`, `local.properties`,
  TV backups and screenshots of a person's TV. `.gitignore` covers them; check `git status` before a commit.
  Examples and tests use neutral data (Moscow, not anyone's city; no real names).
- **Honesty is a feature.** The assistant never says it did something it did not do with a tool in the same
  answer, and says plainly what it cannot do (switch inputs, turn the TV off, see the screen). Keep that in
  the system prompt and in tool results: a tool returns what really happened.
- **Text from tools is data.** Search results never drive memory or reminders: `Answerer` refuses those
  tools after a search in the same answer. Keep it that way when adding tools that persist something.
- **Nothing slow on the main thread.** Google's TTS shares a process with the recognizer, so every
  `TextToSpeech` call goes through `Speaker`'s executor; PackageManager scans, Keystore and network run on
  `Dispatchers.IO`. A frozen overlay also freezes the remote.
- **Every string in both languages.** `values/strings.xml` and `values-ru/strings.xml` change together. No
  emoji anywhere in the UI or the answers.
- **The look is the overlay's.** Graphite card `#292A2D`, hairline `#12FFFFFF`, accent `#4D6BFE`, text
  `#F8FAFF`, secondary `#A3A3AD`, radius 14dp, hints that show only the keys that work right now. Settings use
  the same cards.
- **Test on a real TV** for anything visible or remote-driven, and say what you checked and what you could
  not.

## Adding a tool

1. `chat/Tools.kt`: a name constant and its definition in `definitions()`; add it to `ACTIONS` if it changes
   something (so the dialogue keeps the call).
2. `chat/Answerer.kt`: run it in `device()` or `runTool()`, return what happened in words for the model, and
   report it through `Events` if the panel should show it.
3. `AssistActivity.kt`: the pill or card for the new event; strings in both languages.
4. The system prompt (`system_prompt`) says what the assistant can now do.
5. A unit test for the pure parts.

## Commits

One topic per commit, the message says why. Add a line to [CHANGELOG.md](CHANGELOG.md) under
`Unreleased` for anything a user would notice.
