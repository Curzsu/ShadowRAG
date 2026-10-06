package com.yizhaoqi.smartpai.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ModelHttpPropertiesTest {
    @Test void everyLimitRejectsZeroAndNegativeValues() {
        List<Consumer<ModelHttpProperties>> invalid = List.of(p -> p.setMaxSseLineBytes(0),
                p -> p.setMaxSseFrameBytes(-1), p -> p.setMaxStreamContentChars(0),
                p -> p.setMaxToolArgumentsChars(-1), p -> p.setMaxJsonResponseBytes(0),
                p -> p.setMaxErrorResponseBytes(-1), p -> p.setConnectTimeoutMs(0));
        for (var configure : invalid) {
            var p = new ModelHttpProperties(); configure.accept(p);
            assertThrows(IllegalArgumentException.class, p::validate);
        }
    }
}
