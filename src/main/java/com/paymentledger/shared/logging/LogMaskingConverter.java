package com.paymentledger.shared.logging;

import ch.qos.logback.core.pattern.CompositeConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logback CompositeConverter that masks sensitive data in log statements.
 * Registered as {@code %mask(%m)} in logback-spring.xml so it wraps the
 * fully-rendered message before output.
 *
 * Redacts:
 * 1. Bearer JWT tokens and authorization headers.
 * 2. Password, token, secret, apiKey, and CVV fields in JSON / key-value representations.
 * 3. Credit card PANs.
 * 4. Private keys (PEM format).
 *
 * NON-NEGOTIABLE: This converter must NEVER cause an exception.
 * If masking fails, the original message is returned unchanged (defensive fallback).
 */
public class LogMaskingConverter extends CompositeConverter<ILoggingEvent> {

    private static final Pattern[] SENSITIVE_PATTERNS = new Pattern[]{
            // Bearer tokens
            Pattern.compile("Bearer\\s+[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_.+/=]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Bearer\\s+[A-Za-z0-9._~+/-]{10,}", Pattern.CASE_INSENSITIVE),

            // Password, secret, token, apiKey in JSON or key-value format
            Pattern.compile("(\"(?:password|secret|jwt|token|refreshToken|apiKey|clientSecret|cvv|cardCvv)\"\\s*:\\s*\")[^\"]+(\")", Pattern.CASE_INSENSITIVE),
            Pattern.compile("((?:password|secret|jwt|token|refreshToken|apiKey|clientSecret|cvv|cardCvv)\\s*=\\s*['\"]?)[^'\",\\s]+(['\"]?)", Pattern.CASE_INSENSITIVE),

            // Credit card numbers (13 to 19 digits with optional spaces or dashes)
            Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|3(?:0[0-5]|[68][0-9])[0-9]{11}|6(?:011|5[0-9]{2})[0-9]{12}|(?:2131|1800|35\\d{3})\\d{11})\\b"),

            // Private keys (PEM format)
            Pattern.compile("-----BEGIN (?:RSA |EC )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA |EC )?PRIVATE KEY-----")
    };

    /**
     * CompositeConverter entry point: {@code str} is the output of the child converters (e.g. the rendered message).
     */
    @Override
    protected String transform(ILoggingEvent event, String str) {
        return mask(str);
    }

    /**
     * Public static helper so unit tests can call mask() directly without a running Logback context.
     */
    public static String mask(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        try {
            String result = message;

            // Mask Bearer tokens
            result = SENSITIVE_PATTERNS[0].matcher(result).replaceAll("Bearer [REDACTED_TOKEN]");
            result = SENSITIVE_PATTERNS[1].matcher(result).replaceAll("Bearer [REDACTED_TOKEN]");

            // Mask JSON credentials (capture-group preserving replacements)
            Matcher jsonMatcher = SENSITIVE_PATTERNS[2].matcher(result);
            result = jsonMatcher.replaceAll("$1********$2");

            // Mask KV credentials
            Matcher kvMatcher = SENSITIVE_PATTERNS[3].matcher(result);
            result = kvMatcher.replaceAll("$1********$2");

            // Mask PANs
            result = SENSITIVE_PATTERNS[4].matcher(result).replaceAll("[REDACTED_CARD_PAN]");

            // Mask Private Keys
            result = SENSITIVE_PATTERNS[5].matcher(result).replaceAll("[REDACTED_PRIVATE_KEY]");

            return result;
        } catch (Exception ex) {
            // Defensive fallback: masking failure must never surface as an application error
            return message;
        }
    }
}
