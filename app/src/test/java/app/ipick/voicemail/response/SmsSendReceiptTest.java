package app.ipick.voicemail.response;

import org.junit.Test;
import static org.junit.Assert.*;

public class SmsSendReceiptTest {
    private final SmsSendReceipt<Object> receipts = new SmsSendReceipt<>();
    private final Object call = new Object();

    @Test public void allDistinctPartsMustSucceedBeforeRejectingOnce() {
        receipts.start("first", call, 2, 2);
        assertNull(receipts.result("first", 1, true, 2));
        assertNull(receipts.result("first", 1, true, 2));
        assertSame(call, receipts.result("first", 0, true, 2));
        assertNull(receipts.result("first", 0, true, 2));
    }

    @Test public void failedPartVetoesRejection() {
        receipts.start("first", call, 2, 2);
        assertNull(receipts.result("first", 0, false, 2));
        assertNull(receipts.result("first", 1, true, 2));
    }

    @Test public void answeredCallCannotBeRejectedByALateResult() {
        receipts.start("first", call, 2, 1);
        receipts.forget(call);
        assertNull(receipts.result("first", 0, true, 2));
    }

    @Test public void oldResultCannotRejectOrCancelANewCall() {
        receipts.start("first", call, 2, 1);
        Object next = new Object();
        receipts.start("next", next, 2, 1);
        receipts.forget(call);
        assertNull(receipts.result("first", 0, true, 2));
        assertNull(receipts.result("first", 0, false, 2));
        assertSame(next, receipts.result("next", 0, true, 2));
    }

    @Test public void changingModeInvalidatesAnOutstandingResult() {
        receipts.start("first", call, 2, 1);
        assertNull(receipts.result("first", 0, true, 4));
    }
}
