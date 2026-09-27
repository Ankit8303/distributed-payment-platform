package com.paymentledger.notification.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Constrained template rendering engine.
 * Strictly performs safe variable substitution of the format {{variableName}}.
 * Prohibits SpEL, script execution, arbitrary Java expressions, or executable code evaluation.
 */
@Component
public class NotificationTemplateEngine {

    private static final Logger log = LoggerFactory.getLogger(NotificationTemplateEngine.class);
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)\\}\\}");

    /**
     * Renders a template string by substituting {{varName}} with values from the model.
     * Unknown variables are replaced with empty strings.
     * Unsafe script/expression patterns are never evaluated.
     */
    public String render(String template, Map<String, Object> context) {
        if (template == null) {
            return "";
        }
        if (context == null || context.isEmpty()) {
            return VARIABLE_PATTERN.matcher(template).replaceAll("");
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String variableName = matcher.group(1);
            Object value = context.get(variableName);
            String replacement = value != null ? sanitize(value.toString()) : "";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Sanitizes input to avoid leaking control characters or executable injection.
     */
    private String sanitize(String input) {
        if (input == null) {
            return "";
        }
        // Strip non-printable control characters except standard whitespace
        return input.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
    }
}
