# Hold Volume Up — implementation and validation

The one-second hold is opt-in and only acts on the single current incoming ringing call. The same manual action powers the notification. The Accessibility service receives button events but requests no screen content. It passes events through so the system may also silence the ringtone.

## Checked

- Eight VolumeHold tests: threshold, short press/release, repeats, no active call, changed call, cancellation, and a new hold after release.
- Ten AnswerMachine manual tests: works with automatic voicemail disabled, bypasses its delay, no duplicate answer, no future-call arming, stale/ended tokens rejected, existing-call/emergency/competing-call/missing-greeting checks retained.
- Manifest service binding permission, private notification receiver, key-filter capability and no screen-content capability.

Result: 18 tests, zero failures. Focused command: `./gradlew --offline :daemon-tests:test --tests '*VolumeHoldTest' --tests '*AnswerMachineTest.manual*'`.

## Not checked yet

No app build or Android compilation was requested or performed. Android integration, accessibility setup, hardware delivery while locked, and real-call playback still require validation with a later APK. Another accessibility key-filter service or phone firmware may prevent delivery. Keep the notification action available as an alternative.

The existing 0.5.1 APK does not contain these unreleased changes.
