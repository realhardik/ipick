# IPick

IPick answers calls you miss with a voicemail greeting or an automatic text reply.

## Features

- **Voicemail:** play a greeting and let callers leave a message.
- **SMS:** send a reply, then decline the call.
- **Idle, DND and Custom statuses:** choose the greeting or text used for incoming calls.
- **Response timer:** choose how long to let calls ring before responding.
- **Personal greetings:** record your voice or turn typed text into speech.
- **Send to voicemail:** send a ringing call straight to voicemail from a notification.
- **Pick up during voicemail:** join the same call while someone is leaving a message.
- **Exceptions:** leave selected callers alone so their calls ring normally.
- **Screen off during voicemail:** turn off the screen when the greeting starts.
- **Inbox:** listen to saved voicemail messages.

## First-time setup

IPick currently needs Android 12 or newer. Voicemail support varies by phone, so check it with a call from another phone before relying on it.

1. Install the APK and open **IPick**.
2. Connect to Wi-Fi and follow the setup prompts. The app walks you through permissions, Developer options, USB debugging and Wireless debugging.
3. Allow notifications for pairing and incoming-call actions. Phone and contacts access help identify callers.
4. Follow the prompts to let IPick run in the background.
5. For voicemail, turn on **Enable IPick** in **Settings → General** and select **Voicemail**. Open **Settings → Voicemail audio → Check audio support**. Once available, use **Test next incoming call** and call from another phone to check that the greeting can be heard and a message is saved.

Wi-Fi is needed for setup and may be needed again after restarting your phone or updating the app. Once IPick is ready, it can handle calls without Wi-Fi. Keep the debugging settings enabled while using it.

To let IPick restart voicemail away from Wi-Fi, enable **Settings → Phone & connection → Start without Wi-Fi** while connected to Wi-Fi. After each phone restart, connect to Wi-Fi once to prepare it again. This optional setting opens debugging port 5555 on the networks your phone joins while USB debugging is on. Other devices can request debugging access, and the older connection is unencrypted. The setting is off by default.

## Voicemail

IPick answers an incoming call, plays the greeting for your current status, and records the caller's message after the tone. Callers hear one short beep at the end of every greeting. Messages appear in **Inbox**.

To enable it:

1. Open **Settings → General** and choose **Voicemail** under **Respond with**.
2. Open **Greetings** and choose or create a greeting.
3. Return to **Settings → General**, select your status outside DND and ring time, and turn on **Enable IPick**.

Turn off **Enable IPick** to stop both responses. Your response choice stays saved, and turning IPick off leaves the voicemail helper running.

## SMS

IPick sends the text for your current status, then declines the call once sending succeeds. If sending fails, it leaves the call ringing. A call you answer before the reply is sent gets no automatic reply.

To enable it:

1. Open **Settings → General**, turn on **Enable IPick**, and choose **SMS** under **Respond with**.
2. Allow the requested phone and SMS permissions.
3. Open **Greetings**. This tab now shows text replies instead of voicemail greetings.
4. Choose **Idle**, **DND** or **Custom**, write your reply, and tap **Save text reply**. You can also start with **Use default message**.
5. Return to **Settings → General** to choose your status outside DND and ring time.

SMS works while the voicemail helper is offline as long as Android still delivers incoming calls to IPick. Texts use the SIM that received the call. Normal SMS charges apply, and the caller must have a visible number that can receive texts.

To prevent repeated replies, IPick sends at most one reply to the same number every 10 minutes, with a total limit of 10 SMS parts per hour and 30 per 24 hours across both SIMs. Long texts can use several SMS parts. If a limit is reached, the call keeps ringing. Replies that fail after submission still count toward these limits.

## Status and response timer

In normal, silent and vibrate mode, IPick uses your Idle or Custom choice and ring timer from **Settings → General**. When the phone enters DND, IPick switches to your DND greeting or text and responds immediately. Turning DND off restores your normal choice.

Edit the saved greetings or texts in **Greetings**. Check your response status in the **Inbox**.

Under **Settings → Do Not Disturb**, you can add DND-only exceptions and enable repeated calls. The third call and later calls from the same visible number within 15 minutes are left for you to answer. Repeated calls are enabled by default.

Your phone controls sound during DND. IPick leaves eligible calls alone, while the phone’s DND sound rules still apply. **Open phone DND settings** takes you to those rules.

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

Editing text or changing your name does not change saved audio until you generate a new greeting. Editing a status also does not make it active—choose Idle or Custom in **Settings → General**. DND follows your phone.

## Send a call straight to voicemail

While a call is ringing, open the notification shade and tap **Send to voicemail**. This skips the ring timer for that call.

It also works when SMS is selected, as long as a text has not already started sending. Your usual response mode stays the same for later calls.

## Pick up during a message

You can take over while the greeting plays or the caller leaves a message.

1. Wake your phone if needed and open the notification shade.
2. Tap **Pick up** in the voicemail notification.
3. Continue talking on the same call. Voicemail recording stops when you take over.

## Exceptions

Call exceptions always bypass IPick. DND exceptions apply only while the phone is in DND.

1. Open **Settings → General → Call exceptions** or **Settings → Do Not Disturb → DND exceptions**.
2. Tap **Add a contact** or **Add contact**.
3. Choose a phone number in your contacts app and tap **Done** in IPick.
4. To delete entries, tap **Remove**, select contacts, and confirm removal.

## Turn the screen off during voicemail

This option turns the screen off once greeting playback starts. It leaves the normal ringing screen alone, and you can wake the phone normally afterward.

1. Open **Settings → Voicemail audio**.
2. Enable **Turn screen off during voicemail**.

Your normal screen lock still applies. This feature is new and still needs confirmation on real phones.

## Listen to messages

Open **Inbox** and tap a message to play or pause it. Contacts access supplies names. When it is denied, new voicemail messages retain the number Android provided. Hidden or unavailable numbers appear as Unknown caller. Saved messages remain available when you switch to SMS.

## If something stops working

- **After a restart or update:** connect to Wi-Fi, open IPick, and follow any setup prompts to reconnect it.
- **Greeting generation fails:** try again later or record your own voice.
- **Voicemail answers silently:** check audio support in Settings and try a call from another phone. Use **Copy last-call report** when reporting the problem.
- **No notifications:** allow notifications for IPick in your phone's settings.

IPick needs your phone to be switched on and able to receive calls. It cannot take voicemail while the phone is off or out of coverage. Disable automatic answering in other apps to avoid two apps responding to the same call.

