package com.paicli;

import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class LoggingConfigurationTest {
    @Test
    void slf4jHasRealBackendAndRollingFileConfigurationIsBounded() throws Exception {
        assertInstanceOf(LoggerContext.class, LoggerFactory.getILoggerFactory());
        String xml = new String(LoggingConfigurationTest.class.getResourceAsStream(
                "/logback.xml").readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(xml.contains("${user.home}/.paicli/logs"));
        assertTrue(xml.contains("<maxFileSize>5MB</maxFileSize>"));
        assertTrue(xml.contains("<maxHistory>3</maxHistory>"));
        assertFalse(xml.contains("ConsoleAppender"));
    }
}
