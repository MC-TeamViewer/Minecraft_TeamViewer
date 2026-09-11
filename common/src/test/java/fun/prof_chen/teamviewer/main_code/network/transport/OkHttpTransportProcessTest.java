package fun.prof_chen.teamviewer.main_code.network.transport;

import fun.prof_chen.teamviewer.main_code.network.abstraction.TransportOptions;
import fun.prof_chen.teamviewer.main_code.network.transport.OkHttpTransportProcess.WsNegotiation;
import org.junit.jupiter.api.Test;

import java.net.ProtocolException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkHttpTransportProcessTest {
    @Test
    void acceptsSupportedServerWindowBits() throws ProtocolException {
        assertEquals(8, OkHttpTransportProcess.parseServerMaxWindowBits("8"));
        assertEquals(15, OkHttpTransportProcess.parseServerMaxWindowBits("15"));
    }

    @Test
    void rejectsNonNumericServerWindowBitsAsProtocolError() {
        ProtocolException error = assertThrows(ProtocolException.class,
                () -> OkHttpTransportProcess.parseServerMaxWindowBits("invalid"));

        assertTrue(error.getMessage().contains("invalid"));
        assertInstanceOf(NumberFormatException.class, error.getCause());
    }

    @Test
    void rejectsUnsupportedServerWindowBits() {
        ProtocolException tooSmall = assertThrows(ProtocolException.class,
                () -> OkHttpTransportProcess.parseServerMaxWindowBits("7"));
        ProtocolException tooLarge = assertThrows(ProtocolException.class,
                () -> OkHttpTransportProcess.parseServerMaxWindowBits("16"));

        assertTrue(tooSmall.getMessage().contains("7"));
        assertTrue(tooLarge.getMessage().contains("16"));
    }

    @Test
    void compressionMasterSwitchOffYieldsPurePlaintext() {
        WsNegotiation negotiation = WsNegotiation.forSuite(false, TransportOptions.SUITE_ZSTD_DICT, false);

        assertFalse(negotiation.deflateOffered());
        assertNull(negotiation.offeredSubprotocols());
    }

    @Test
    void zstdSuitesOfferZstdThenPlainWithDeflate() {
        for (String suite : new String[] {TransportOptions.SUITE_ZSTD, TransportOptions.SUITE_ZSTD_DICT}) {
            WsNegotiation negotiation = WsNegotiation.forSuite(true, suite, false);

            assertTrue(negotiation.deflateOffered(), suite);
            assertEquals("teamviewrelay.zstd.v1, teamviewrelay.plain.v1", negotiation.offeredSubprotocols(), suite);
        }
    }

    @Test
    void plainSuiteOffersOnlyPlainSubprotocol() {
        WsNegotiation negotiation = WsNegotiation.forSuite(true, TransportOptions.SUITE_PLAIN, false);

        assertTrue(negotiation.deflateOffered());
        assertEquals("teamviewrelay.plain.v1", negotiation.offeredSubprotocols());
    }

    @Test
    void plainSuiteCanDropDeflateEntirely() {
        WsNegotiation negotiation = WsNegotiation.forSuite(true, TransportOptions.SUITE_PLAIN, true);

        assertFalse(negotiation.deflateOffered());
        assertEquals("teamviewrelay.plain.v1", negotiation.offeredSubprotocols());
    }

    @Test
    void unknownSuiteNormalizesToZstdDict() {
        WsNegotiation negotiation = WsNegotiation.forSuite(true, "bogus", false);

        assertTrue(negotiation.deflateOffered());
        assertEquals("teamviewrelay.zstd.v1, teamviewrelay.plain.v1", negotiation.offeredSubprotocols());
    }
}
