# IPick

IPick answers calls you miss with a voicemail greeting or an automatic text reply.

## Features

- **Voicemail:** play a greeting and let callers leave a message.
- **Auto-text:** send a reply, then decline the call.
- **Idle, DND and Custom statuses:** choose the greeting or text used for incoming calls.
- **Response timer:** choose how long to let calls ring before responding.
- **Personal greetings:** record your voice or turn typed text into speech.
- **Send to voicemail:** send a ringing call straight to voicemail from a notification.
- **Volume button shortcut:** hold Volume Up to send a call to voicemail.
- **Pick up during voicemail:** join the same call while someone is leaving a message.
- **Exceptions:** leave selected callers alone so their calls ring normally.
- **Screen off during voicemail:** turn off the screen when the greeting starts.
- **Inbox:** listen to saved voicemail messages.

## First-time setup

IPick currently needs Android 12 or newer. Voicemail support varies by phone, so check it with a call from another phone before relying on it.

1. Install the APK and open **Voicemail**.
2. Connect to Wi-Fi and follow the setup prompts. The app walks you through permissions, Developer options, USB debugging and Wireless debugging.
3. Allow notifications so you can see your status and incoming-call actions.
4. Follow the prompts to let IPick run in the background.
5. Open **Settings → Check audio support**. Once available, use **Test next incoming call** and call from another phone to check that the greeting can be heard and a message is saved.

Wi-Fi is needed for setup and may be needed again after restarting your phone or updating the app. Once IPick is ready, it can handle calls without Wi-Fi. Keep the debugging settings enabled while using it.

## Voicemail

IPick answers an incoming call, plays the greeting for your current status, and records the caller's message after the tone. Messages appear in **Inbox**.

To enable it:

1. Open **Settings** and choose **Voicemail** under **When I miss a call**.
2. Open **Greetings** and choose or create a greeting.
3. Return to **Settings**, select your status and ring time, and turn on **Take voicemail messages**.

Turn off **Take voicemail messages** to stop automatic voicemail answering.

## Auto-text

IPick sends the text for your current status, then declines the call once sending succeeds. If sending fails, it leaves the call ringing. A call you answer before the reply is sent gets no automatic reply.

To enable it:

1. Open **Settings → When I miss a call → Auto-text**.
2. Allow the requested phone and SMS permissions.
3. Open **Greetings**. This tab now shows text replies instead of voicemail greetings.
4. Choose **Idle**, **DND** or **Custom**, write your reply, and tap **Save text reply**. You can also start with **Use default message**.
5. Return to **Settings** to choose your active status and ring time.

Voicemail and Auto-text are alternatives: only one handles missed calls automatically. Texts use the SIM that received the call. Normal SMS charges apply, and the caller must have a visible number that can receive texts.

## Status and response timer

Your status selects which saved greeting or text IPick uses:

- **Idle:** waits for your chosen ring time before responding.
- **DND:** starts your selected response immediately—voicemail or Auto-text.
- **Custom:** uses your custom greeting or reply after the chosen ring time.

To change them:

1. Open **Settings → Current status** and select a status. You can also change it from the **Current status** notification.
2. Tap **Idle / Custom ring time** to choose between 5 and 30 seconds. The default is 20 seconds.
3. Edit the greeting or text for each status in **Greetings**.

IPick's DND status is separate from your phone's Do Not Disturb setting. The optional **Use 5 seconds for calls silenced by phone DND** setting shortens the wait for those calls.

If the caller hangs up or the network ends the call before the timer finishes, IPick cannot respond to it.

## Personal greetings

Each status has its own saved greeting. You can use the included greetings, record your voice, or generate speech from text.

To record your voice:

1. Select **Voicemail** in **Settings**, then open **Greetings**.
2. Choose the status you want to edit.
3. Tap **Record my voice → Start recording**.
4. Speak your greeting, then tap **Stop and save recording**. You can record up to 30 seconds.
5. Tap **Listen to saved greeting** to hear what callers will hear.

To create a greeting from text:

1. Enter **Your name** in **Settings** if you want it included in the default message.
2. Open **Greetings**, select a status, and choose **Type a message**.
3. Write your greeting or tap **Use default message**.
4. Choose a voice and tap **Generate and save**.
5. Listen to the saved greeting before using it.

Voice generation needs internet access and sends your typed greeting to an online voice service. No account or API key is required. The service may be unavailable; if generation fails, your previous saved greeting stays in place. Saved greetings play without internet access.

Editing text or changing your name does not change saved audio until you generate a new greeting. Editing a status also does not make it active—select it in **Settings** when you want to use it.

## Send a call straight to voicemail

While a call is ringing, open the notification shade and tap **Send to voicemail**. This skips the ring timer for that call.

It also works when Auto-text is selected, as long as a text has not already started sending. Your usual response mode stays the same for later calls.

## Hold Volume Up

This optional shortcut sends a ringing call to voicemail without opening the notification.

1. Open **Settings → Call shortcuts**.
2. Turn on **Hold Volume Up**.
3. Tap **Open shortcut access** and enable **Voicemail volume shortcut** in Android's Accessibility settings.
4. When a call rings, hold Volume Up for one second.

Button access while the phone is locked depends on the phone. Pressing the button may also silence the ringtone. The notification action is available as another way to send the call to voicemail.

## Pick up during a message

You can take over while the greeting plays or the caller leaves a message.

1. Wake your phone if needed and open the notification shade.
2. Tap **Pick up** in the voicemail notification.
3. Continue talking on the same call. Voicemail recording stops when you take over.

## Exceptions

Callers on this list ring normally. IPick does not answer them, send them automatic texts, or offer its send-to-voicemail shortcuts for their calls.

1. Open **Settings → Exceptions → Manage exceptions**.
2. Enter the phone number with its country code, such as **+91**, and optionally a name.
3. Tap **Add exception**.
4. To allow IPick to respond to that caller again, return to the list and tap **Remove**.

## Turn the screen off during voicemail

This option turns the screen off once greeting playback starts. It leaves the normal ringing screen alone, and you can wake the phone normally afterward.

1. Open **Settings**.
2. Enable **Turn screen off during voicemail**.

Your normal screen lock still applies. This feature is new and still needs confirmation on real phones.

## Listen to messages

Open **Inbox** and tap a message to play or pause it. Saved messages remain available when you switch to Auto-text.

## If something stops working

- **After a restart or update:** connect to Wi-Fi, open IPick, and follow any setup prompts to reconnect it.
- **Greeting generation fails:** try again later or record your own voice.
- **Voicemail answers silently:** check audio support in Settings and try a call from another phone. Use **Copy last-call report** when reporting the problem.
- **No notifications:** allow notifications for Voicemail in your phone's settings.

IPick needs your phone to be switched on and able to receive calls. It cannot take voicemail while the phone is off or out of coverage. Disable automatic answering in other apps to avoid two apps responding to the same call.

