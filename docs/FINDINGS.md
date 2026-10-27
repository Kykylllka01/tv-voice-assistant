# How the remote's voice button reaches a third-party app

Measured on a DIGMA DM-LED65UQB31 (Changhong "AI PONT", MediaTek m7632, Google TV 14), with a Bluetooth
remote "TVC0GWBT" (vendor 1d5a, product c082), the Google Assistant (`com.google.android.katniss`) disabled.

1. **The voice button sends no key event.** `getevent` shows nothing for it. The remote speaks the Android TV
   voice protocol to `com.google.android.tv.remote.service` (`AtvRemote`), which logs
   `BleRemoteDevice: HTT session initiated` (hold-to-talk).
2. **AtvRemote starts the assistant role holder.** With the role empty it logs `Package null doesn't hold
   RECORD_AUDIO` and nothing opens. An app with an activity for `android.intent.action.ASSIST`, made holder with
   `cmd role add-role-holder android.app.role.ASSISTANT <package>`, is started by every press:
   `START u0 {act=android.intent.action.ASSIST ... (has extras)}`, extras `ASSIST_PACKAGE` = the app in front.
3. **The remote's own audio channel is closed to apps.** `AtvSearchAssistantService` is guarded by
   `android.permission.BIND_TV_REMOTE_SERVICE`, `prot=signature|privileged`.
4. **But while the button is held, the remote's audio is mixed into a remote-submix input** — the audio HAL logs
   `r_submix adev_open_input_stream(addr=...:ap:4mixr:0)` — and an ordinary `SpeechRecognizer`
   (`com.google.android.tts` `GoogleTTSRecognitionService`) recording from the default voice source hears it.
   3 of 3 phrases recognized with the button held, Assistant disabled.
5. **Pressed and released, the recognizer gets the TV's built-in far-field mic** (`dev:builtin-mic`, 4 DMICs,
   with a hardware mute read from `content://atv.hotwordmic/togglestate`). Muted, it delivers a constant
   value (95 974 of 96 000 samples = -21427), which the recognizer reports as error 7 (no match).
6. **With the Assistant enabled, recognition is flaky**: its hotword detector opens and closes the DMICs every few
   seconds (`MAD_ALSA dmic enable/disable`) and competes for the input. Calling its
   `VoiceInputActivity` for `RECOGNIZE_SPEECH` from a third-party app returned `RESULT_CANCELED`, no words.

7. **After a voice session the remote stays "open", so presses alternate.** The audio only arrives when AtvRemote
   registers its bridge (`AtvRemote.AudioBridgeX: Ad hoc audio policy is being registered`, then the remote-submix
   input above), and that happens only when the remote actually streams. Pressed again with no other key in
   between, the remote does not stream; the presses alternate fail / work / fail. One physical key press
   (an arrow, Back) in between makes the next voice press work every time; the same key injected with
   `input keyevent` does not, so the state lives in the remote, not on the TV. The privileged assistant presumably
   closes the remote's microphone through `AtvSearchAssistantService`, which a third-party app cannot reach.
   The overlay turns that into the interaction: "next question: press ▶, then hold the voice button".

So: **hold the voice button while speaking**, press an arrow before the next question, and keep the Assistant
disabled.
