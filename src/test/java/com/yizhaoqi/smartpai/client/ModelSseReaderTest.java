package com.yizhaoqi.smartpai.client;

import com.yizhaoqi.smartpai.config.ModelHttpProperties;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelSseReaderTest {
    private List<String> read(String input, ModelHttpProperties properties) throws IOException {
        var frames = new ArrayList<String>();
        new ModelSseReader(properties).read(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), frames::add);
        return frames;
    }
    @Test void utf8SurvivesEveryNetworkByteBoundary() throws Exception {
        byte[] bytes = "data: 中文😀\n\n".getBytes(StandardCharsets.UTF_8);
        var frames = new ArrayList<String>();
        InputStream fragmented = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(1, len)); }
        };
        new ModelSseReader(new ModelHttpProperties()).read(fragmented, frames::add);
        assertEquals(List.of("中文😀"), frames);
    }
    @Test void crlfCommentsAndMultilineDataProduceLogicalFrames() throws Exception {
        assertEquals(List.of("one\ntwo", "中文"), read(": comment\r\nevent: ignored\r\ndata: one\r\ndata:two\r\n\r\ndata: 中文\n\n", new ModelHttpProperties()));
    }
    @Test void unterminatedFrameIsNotDispatched() throws Exception {
        assertEquals(List.of("complete"), read("data: complete\n\ndata: partial", new ModelHttpProperties()));
    }
    @Test void oversizedLineFailsBeforeUnboundedBuffering() {
        var p = new ModelHttpProperties(); p.setMaxSseLineBytes(12);
        assertThrows(IOException.class, () -> read("data: " + "x".repeat(100), p));
    }
    @Test void oversizedFrameIncludesCommentsAndAllDataLines() {
        var p = new ModelHttpProperties(); p.setMaxSseFrameBytes(25);
        assertThrows(IOException.class, () -> read(":123456789\ndata: 1234\ndata: 5678\n\n", p));
    }
    @Test void invalidUtf8IsRejected() {
        assertThrows(IOException.class, () -> new ModelSseReader(new ModelHttpProperties()).read(
                new ByteArrayInputStream(new byte[]{'d','a','t','a',':',' ',(byte)0xc3,0x28,'\n','\n'}), ignored -> {}));
    }
    @Test void frameLimitCountsBothBytesOfCrLf() {
        var p = new ModelHttpProperties(); p.setMaxSseFrameBytes(16);
        assertThrows(IOException.class, () -> read("data: x\r\n:12345\r\n\r\n", p));
    }
}
