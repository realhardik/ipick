package app.ipick.voicemail.shell;

import app.ipick.voicemail.shortcuts.VolumeHold;
import org.junit.Test;
import static org.junit.Assert.*;

public class VolumeHoldTest {
    @Test public void shortPressDoesNothing() {
        VolumeHold h = new VolumeHold();
        assertTrue(h.down("call-a", 100, 1, 0));
        assertNull(h.fire("call-a", 1099));
        h.up(1);
        assertNull(h.fire("call-a", 1100));
    }
    @Test public void firesOnceAtOneSecond() {
        VolumeHold h = new VolumeHold();
        h.down("call-a", 100, 1, 0);
        assertEquals("call-a", h.fire("call-a", 1100));
        assertNull(h.fire("call-a", 2100));
    }
    @Test public void noRingingCallDoesNothing() {
        VolumeHold h = new VolumeHold();
        assertFalse(h.down(null, 100, 1, 0));
        assertNull(h.fire("later-call", 1100));
    }
    @Test public void cannotApplyToAnotherCall() {
        VolumeHold h = new VolumeHold();
        h.down("ended-call", 100, 1, 0);
        assertNull(h.fire("new-call", 1100));
        assertNull(h.fire(null, 1100));
    }
    @Test public void repeatsDoNotExtendOrRetrigger() {
        VolumeHold h = new VolumeHold();
        h.down("call-a", 100, 1, 0);
        assertFalse(h.down("call-a", 600, 1, 1));
        assertEquals("call-a", h.fire("call-a", 1100));
        assertFalse(h.down("call-a", 1200, 1, 2));
        assertNull(h.fire("call-a", 2200));
    }
    @Test public void cancellationDisarmsPendingHold() {
        VolumeHold h = new VolumeHold();
        h.down("call-a", 100, 1, 0);
        h.cancel();
        assertNull(h.fire("call-a", 1100));
    }
    @Test public void repeatWithoutInitialDownCannotTrigger() {
        VolumeHold h = new VolumeHold();
        assertFalse(h.down("call-a", 100, 1, 3));
        assertNull(h.fire("call-a", 1100));
    }
    @Test public void releaseAllowsNewHold() {
        VolumeHold h = new VolumeHold();
        h.down("call-a", 100, 1, 0);
        h.up(1);
        assertTrue(h.down("call-b", 2000, 1, 0));
        assertEquals("call-b", h.fire("call-b", 3000));
    }
}
