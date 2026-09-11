package fun.prof_chen.teamviewer.main_code.network.abstraction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransportOptionsTest {
    @Test
    void normalizeSuiteAcceptsKnownSuitesCaseInsensitively() {
        assertEquals(TransportOptions.SUITE_PLAIN, TransportOptions.normalizeSuite("plain"));
        assertEquals(TransportOptions.SUITE_ZSTD, TransportOptions.normalizeSuite(" ZSTD "));
        assertEquals(TransportOptions.SUITE_ZSTD_DICT, TransportOptions.normalizeSuite("Zstd-Dict"));
    }

    @Test
    void normalizeSuiteDefaultsUnknownValuesToZstdDict() {
        assertEquals(TransportOptions.SUITE_ZSTD_DICT, TransportOptions.normalizeSuite(null));
        assertEquals(TransportOptions.SUITE_ZSTD_DICT, TransportOptions.normalizeSuite(""));
        assertEquals(TransportOptions.SUITE_ZSTD_DICT, TransportOptions.normalizeSuite("deflate"));
    }
}
