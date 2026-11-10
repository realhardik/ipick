package app.ipick.voicemail.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The answering machine's settings off the wire: anything unclear is off. */
public class AnswerSettingsTest {

    @Test
    public void screenSleepIsOptInAndSurvivesTheWire() {
        assertFalse(AnswerSettings.parse("1,60,music").screenOff);
        assertFalse(AnswerSettings.parse("1,60,music,0").screenOff);
        AnswerSettings enabled = AnswerSettings.parse("1,60,music,1");
        assertTrue(enabled.on);
        assertTrue(enabled.screenOff);
        assertEquals("1,60,music,1", enabled.wire());
        assertTrue(AnswerSettings.parse(enabled.wire()).screenOff);
    }

    @Test
    public void manualVoicemailCanSleepWithoutEnablingAutomaticVoicemail() {
        AnswerSettings manual = AnswerSettings.parse("0,120,screening,1");
        assertFalse(manual.on);
        assertTrue(manual.screenOff);
        assertEquals(120, manual.maxMessageSeconds);
        assertEquals("screening", manual.route);
        assertFalse(AnswerSettings.TEXT_ONLY.screenOff);
    }

    @Test
    public void invalidScreenSleepFlagDisablesAnswering() {
        assertSame(AnswerSettings.OFF, AnswerSettings.parse("1,60,music,yes"));
        assertSame(AnswerSettings.OFF, AnswerSettings.parse("1,60,music,2"));
        assertSame(AnswerSettings.OFF, AnswerSettings.parse("1,60,music,1,extra"));
    }

    @Test
    public void readsWhatTheAppSends() {
        AnswerSettings s = AnswerSettings.parse("1,60,music");
        assertTrue(s.on);
        assertEquals(60, s.maxMessageSeconds);
        assertEquals("music", s.route);
        assertEquals("1,60,music", s.wire());
        assertFalse(AnswerSettings.parse("0,120,screening").on);
        assertEquals("screening", AnswerSettings.parse("1,30,screening").route);
    }

    @Test
    public void autoTextDisablesVoicemailAndRoundTripsForAcknowledgement() {
        AnswerSettings text = AnswerSettings.parse("text");
        assertSame(AnswerSettings.TEXT_ONLY, text);
        assertFalse(text.on);
        assertEquals("text", text.wire());
    }

    @Test
    public void zeroIsNoLimit() {
        AnswerSettings s = AnswerSettings.parse("1,0,music");
        assertTrue(s.on);
        assertEquals(AnswerSettings.NO_LIMIT, s.maxMessageSeconds);
        assertEquals("1,0,music", s.wire());
    }

    @Test
    public void anythingUnclearIsOff() {
        assertSame(AnswerSettings.OFF, AnswerSettings.parse(null));
        assertSame(AnswerSettings.OFF, AnswerSettings.parse(""));
        assertSame(AnswerSettings.OFF, AnswerSettings.parse("1,60"));
        assertSame(AnswerSettings.OFF, AnswerSettings.parse("1,sixty,music"));
        assertSame(AnswerSettings.OFF, AnswerSettings.parse("1,60,speaker"));
        assertSame(AnswerSettings.OFF, AnswerSettings.parse("1,5,music"));
        assertSame(AnswerSettings.OFF, AnswerSettings.parse("1,6000,music"));
        assertFalse(AnswerSettings.OFF.on);
    }

    @Test
    public void tenSecondsAndTenMinutesAreTheLimitsAndBothAreAllowed() {
        assertEquals(10, AnswerSettings.parse("1,10,music").maxMessageSeconds);
        assertEquals(600, AnswerSettings.parse("1,600,music").maxMessageSeconds);
        assertSame(AnswerSettings.OFF, AnswerSettings.parse("1,9,music"));
        assertSame(AnswerSettings.OFF, AnswerSettings.parse("1,601,music"));
    }
}
