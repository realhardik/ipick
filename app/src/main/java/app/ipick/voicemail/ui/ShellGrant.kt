package app.ipick.voicemail.ui

import app.ipick.voicemail.capture.SystemProperty

/** Some Android builds restrict shell grants through Developer options.
 * Read back the package's permissions because a grant command can return silently on failure.
 */
internal object ShellGrant {

    /** "true" while an OPPO-family phone refuses the shell its grants,
     *  "false" once the switch is on; not set on other phones. */
    const val MONITORING_PROPERTY = "persist.sys.permission.enable"

    /** Debug builds only: act as an OnePlus with the switch off does, on a
     *  phone or emulator that grants. Nothing in the release build sets it. */
    @Volatile
    internal var actAsRefused = false

    /** What #64's phone printed, for [actAsRefused]. */
    internal const val REFUSAL =
        "Exception occurred while executing 'grant':\n" +
            "java.lang.SecurityException: grantRuntimePermission: Neither user 2000 nor current " +
            "process has android.permission.GRANT_RUNTIME_PERMISSIONS.\npm-exit=255"

    enum class Outcome {
        GRANTED,

        /** Android said no, with its SecurityException. */
        REFUSED,

        /** Nothing refused, and nothing granted either. */
        NOT_GRANTED,
    }

    /** The grant: its output and exit status, both on one stream. */
    fun grantCommand(packageName: String, permission: String): String =
        "pm grant $packageName $permission 2>&1; echo pm-exit=\$?"

    /** The read-back: the package's header line, so a dumpsys that ran can
     *  be told from one that did not, and every line naming the permission.
     *  Fixed strings (-F), as a package name is all regex dots. */
    fun readBackCommand(packageName: String, permission: String): String =
        "dumpsys package $packageName | grep -F -e 'Package [$packageName]' -e '${permission.substringAfterLast('.')}'"

    /**
     * [grant]: what [grantCommand] printed. [readBack]: what [readBackCommand]
     * printed for [permission], null if it could not be run.
     *
     * A read-back that names the package is the answer, either way. One that
     * does not - an Android whose dumpsys says it some other way - is no
     * answer, and then a grant that exited 0 counts, as it did before the
     * read-back: blocking setup on a phone that granted would be worse than
     * missing the one that only said it did.
     */
    fun outcome(packageName: String, permission: String, grant: String, readBack: String?): Outcome {
        val lines = readBack.orEmpty().lines().map { it.trim() }
        if (lines.any { it.contains("$permission: granted=true") }) return Outcome.GRANTED
        if (refused(grant)) return Outcome.REFUSED
        val listed = lines.any { it.startsWith("Package [$packageName]") }
        return when {
            listed -> Outcome.NOT_GRANTED
            exitStatus(grant) == 0 -> Outcome.GRANTED
            else -> Outcome.NOT_GRANTED
        }
    }

    /** The SecurityException every gate known so far throws for uid 2000. */
    fun refused(grant: String): Boolean =
        grant.contains("SecurityException") || grant.contains("GRANT_RUNTIME_PERMISSIONS")

    private fun exitStatus(grant: String): Int? =
        Regex("""pm-exit=(\d+)""").find(grant)?.groupValues?.get(1)?.toIntOrNull()

    /** [MONITORING_PROPERTY] as read: true while the switch is off, false
     *  once it is on, null where the phone does not have it or it could not
     *  be read. */
    fun monitoring(property: String?): Boolean? = when (property?.trim()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /**
     * Whether this phone may be refusing the shell, so a card should name the
     * switch: setup's pairing card, for it to be turned on while the user is
     * in Developer options anyway rather than after the grant fails, and the
     * self-test's, where the recorder could not allow exact call tracking.
     * Only an OPPO, OnePlus or realme phone has it, and only there is the
     * property read: it is a getprop, and these run on screen refreshes.
     */
    fun switchMayBeOff(): Boolean =
        DeviceSkin.current() == DeviceSkin.OPLUS && switchMayBeOff(SystemProperty.read(MONITORING_PROPERTY))

    /** [switchMayBeOff] on an OPPO-family phone, from [MONITORING_PROPERTY]
     *  as this app reads it, if it may (sepolicy's call; "" if not): only a
     *  "false" there says the switch is on. */
    internal fun switchMayBeOff(property: String?): Boolean = monitoring(property) != false

    /**
     * The switch, as every card that names it says it. Some builds label it
     * just Permission monitoring, on by default, to be turned off - Shizuku's
     * guide says 'Disable "Permission monitoring"', and Octoclip's notes give
     * ColorOS in Chinese that label - so that wording is named too.
     */
    const val SWITCH = "Disable system optimization, at the end of the Apps section " +
        "(on older phones: Disable permission monitoring; where it says just Permission " +
        "monitoring, turn that off instead). Not there? Set the phone's language to " +
        "English, turn it on, then set the language back"

    /** Why setup did not finish, and what to do. [monitoring]: see [monitoring]. */
    fun advice(outcome: Outcome, skin: DeviceSkin, monitoring: Boolean?): String {
        val what = if (outcome == Outcome.REFUSED) {
            "Android would not let IPick change debugging settings."
        } else {
            "Android did not give IPick the permission to change debugging settings, and did not say why."
        }
        val todo = when {
            monitoring == true || (monitoring == null && skin == DeviceSkin.OPLUS) ->
                "This phone needs one more switch on first. In Developer options, turn on $SWITCH. " +
                    "Then tap Try again, and leave it that way afterwards."
            monitoring == false ->
                "The Developer options switch is already set. Keep Wireless debugging on and tap Try again."
            skin == DeviceSkin.XIAOMI ->
                "Turn on \"USB debugging (Security settings)\" in Developer options, then tap Try again."
            else ->
                "Some phones keep a switch for it in Developer options: Disable system optimization " +
                    "or Disable permission monitoring on OPPO, OnePlus and realme, \"USB debugging " +
                    "(Security settings)\" on Xiaomi. Turn on the one this phone has, then tap Try again."
        }
        return "$what $todo"
    }
}
