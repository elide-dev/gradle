package dev.elide.gradle;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Minimal wire-compatible client for Bazel's length-delimited worker protocol. */
final class ElideWorkerProtocol {
    static final int MAX_FRAME = 64 * 1024 * 1024;

    record Response(int exitCode, String output, int requestId) {}

    static void writeRequest(
            OutputStream stream, List<String> arguments, Map<String, byte[]> inputs, int id)
            throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (String argument : arguments) field(body, 1, argument.getBytes(StandardCharsets.UTF_8));
        for (var input : inputs.entrySet()) {
            ByteArrayOutputStream entry = new ByteArrayOutputStream();
            field(entry, 1, input.getKey().getBytes(StandardCharsets.UTF_8));
            field(entry, 2, input.getValue());
            field(body, 2, entry.toByteArray());
        }
        varint(body, 24);
        varint(body, id);
        if (body.size() > MAX_FRAME) throw new IOException("Elide worker request exceeds 64 MiB");
        varint(stream, body.size());
        body.writeTo(stream);
        stream.flush();
    }

    static Response readResponse(InputStream stream) throws IOException {
        int size = length(stream);
        byte[] bytes = stream.readNBytes(size);
        if (bytes.length != size) throw new EOFException("Truncated Elide worker response");
        ByteArrayInputStream body = new ByteArrayInputStream(bytes);
        int code = 0, id = 0;
        String output = "";
        while (body.available() > 0) {
            long tag = varint(body);
            if (tag == 8) code = (int) varint(body);
            else if (tag == 18) output = new String(readBytes(body), StandardCharsets.UTF_8);
            else if (tag == 24) id = (int) varint(body);
            else {
                switch ((int) (tag & 7)) {
                    case 0 -> varint(body);
                    case 1 -> body.skipNBytes(8);
                    case 2 -> body.skipNBytes(length(body));
                    case 5 -> body.skipNBytes(4);
                    default -> throw new IOException("Invalid Elide worker field");
                }
            }
        }
        return new Response(code, output, id);
    }

    private static byte[] readBytes(InputStream input) throws IOException {
        int size = length(input);
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) throw new EOFException("Truncated worker field");
        return bytes;
    }

    private static int length(InputStream input) throws IOException {
        long size = varint(input);
        if (size < 0 || size > MAX_FRAME)
            throw new IOException("Invalid Elide worker frame length");
        return (int) size;
    }

    private static void field(OutputStream out, int number, byte[] value) throws IOException {
        varint(out, (number << 3) | 2);
        varint(out, value.length);
        out.write(value);
    }

    private static void varint(OutputStream out, long value) throws IOException {
        while ((value & ~127L) != 0) {
            out.write((int) (value & 127) | 128);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static long varint(InputStream input) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = input.read();
            if (b < 0) throw new EOFException("Truncated worker varint");
            if (shift == 63 && (b & 254) != 0) throw new IOException("Worker varint overflow");
            value |= (long) (b & 127) << shift;
            if ((b & 128) == 0) return value;
        }
        throw new IOException("Worker varint overflow");
    }
}
