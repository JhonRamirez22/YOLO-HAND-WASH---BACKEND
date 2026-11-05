package com.handwash.ops;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Collects the formatted messages a class logs, so tests can assert on the OPS_ALERT tokens. */
public final class LogCapture implements AutoCloseable {
    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCapture(Class<?> source) {
        logger = (Logger) LoggerFactory.getLogger(source);
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    public List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    public long alerts(OpsAlert.Code code) {
        return messages().stream().filter(message -> message.startsWith("OPS_ALERT code=" + code.name())).count();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
