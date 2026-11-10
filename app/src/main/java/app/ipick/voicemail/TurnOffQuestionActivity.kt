package app.ipick.voicemail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.ipick.voicemail.capture.RecorderSwitch
import app.ipick.voicemail.ui.TurnOffAwayFromWifiDialog
import app.ipick.voicemail.ui.theme.IPickTheme
import app.ipick.voicemail.ui.theme.Looks

/**
 * The Quick Settings tile's way to ask TurnOffAwayFromWifiDialog: a tile has
 * no screen of its own, so this see-through one holds the same dialog the
 * header's button shows, over whatever was open. A question, not a place, so
 * it keeps no history and stays out of Recents.
 */
class TurnOffQuestionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IPickTheme(Looks.choice(this)) {
                TurnOffAwayFromWifiDialog(
                    onTurnOff = {
                        RecorderSwitch.turnInBackground(applicationContext, false)
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }
}
