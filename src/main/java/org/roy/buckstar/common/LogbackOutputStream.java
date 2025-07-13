package org.roy.buckstar.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;

import java.io.OutputStream;

public class LogbackOutputStream extends OutputStream {
    private final Logger logger;
    private final Level level;
    private final StringBuilder buffer = new StringBuilder();

    public LogbackOutputStream(Logger logger, Level level) {
        this.logger = logger;
        this.level = level;
    }

    @Override
    public void write(int b) {
        if (b == '\n') {
            flush();
        } else {
            buffer.append((char) b);
        }
    }

    @Override
    public void flush() {
        if (!buffer.isEmpty()) {
            switch (level.levelInt) {
                case Level.ERROR_INT:
                    logger.error(buffer.toString());
                    break;
                case Level.WARN_INT:
                    logger.warn(buffer.toString());
                    break;
                case Level.INFO_INT:
                    logger.info(buffer.toString());
                    break;
                case Level.DEBUG_INT:
                    logger.debug(buffer.toString());
                    break;
                case Level.TRACE_INT:
                    logger.trace(buffer.toString());
                    break;
                default:
                    logger.info(buffer.toString());
                    break;
            }
            buffer.setLength(0);
        }
    }
}
