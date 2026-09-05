package dev.elide.gradle;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.List;
import java.util.Map;

class ElideWorkerProtocolTest {
    @Test
    void encodesCanonicalBazelRequest() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ElideWorkerProtocol.writeRequest(out, List.of("--", "A.java"), Map.of(), 7);
        assertArrayEquals(
                new byte[] {14, 10, 2, 45, 45, 10, 6, 65, 46, 106, 97, 118, 97, 24, 7},
                out.toByteArray());
    }

    @Test
    void readsResponseAndRejectsTruncatedFrame() throws Exception {
        var response =
                ElideWorkerProtocol.readResponse(
                        new ByteArrayInputStream(new byte[] {8, 8, 1, 18, 2, 110, 111, 24, 7}));
        assertEquals(1, response.exitCode());
        assertEquals("no", response.output());
        assertEquals(7, response.requestId());
        assertThrows(
                IOException.class,
                () ->
                        ElideWorkerProtocol.readResponse(
                                new ByteArrayInputStream(new byte[] {10, 8})));
        assertThrows(
                IOException.class,
                () ->
                        ElideWorkerProtocol.readResponse(
                                new ByteArrayInputStream(
                                        new byte[] {(byte) 255, (byte) 255, (byte) 255, 127})));
    }
}
