package com.yizhaoqi.smartpai.client;

import com.yizhaoqi.smartpai.config.ModelHttpProperties;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.function.Consumer;

/** Parses complete data frames with byte limits before decoding or growing buffers. */
public final class ModelSseReader {
    private final int maxLine;
    private final int maxFrame;

    public ModelSseReader(ModelHttpProperties properties) {
        properties.validate(); maxLine = properties.getMaxSseLineBytes(); maxFrame = properties.getMaxSseFrameBytes();
    }

    public void read(InputStream body, Consumer<String> onData) throws IOException {
        var input = new PushbackInputStream(new BufferedInputStream(body, 8192), 1);
        var line = new ByteArrayOutputStream();
        var data = new StringBuilder();
        int frameBytes = 0;
        boolean hasData = false;
        int value;
        while ((value = input.read()) != -1) {
            if (++frameBytes > maxFrame) throw new IOException("Model SSE frame limit exceeded");
            if (value != '\n' && value != '\r') {
                if (line.size() >= maxLine) throw new IOException("Model SSE line limit exceeded");
                line.write(value); continue;
            }
            if (value == '\r') {
                int next = input.read();
                if (next == '\n') {
                    if (++frameBytes > maxFrame) throw new IOException("Model SSE frame limit exceeded");
                } else if (next != -1) input.unread(next);
            }
            if (line.size() == 0) {
                if (hasData) onData.accept(data.toString());
                data.setLength(0); hasData = false; frameBytes = 0;
            } else {
                String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(line.toByteArray())).toString();
                if (text.equals("data") || text.startsWith("data:")) {
                    String payload = text.equals("data") ? "" : text.substring(5);
                    if (payload.startsWith(" ")) payload = payload.substring(1);
                    if (hasData) data.append('\n');
                    data.append(payload); hasData = true;
                }
                line.reset();
            }
        }
        // EOF is not a frame delimiter. The model owner requires a dispatched DONE frame.
    }
}
