package com.paymentledger.notification.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTemplateEngineTest {

    private final NotificationTemplateEngine engine = new NotificationTemplateEngine();

    @Test
    @DisplayName("Renders template by safely replacing variables in {{varName}} syntax")
    void safeVariableReplacement() {
        String template = "Hello {{userName}}, your payment of {{amount}} {{currency}} was successful.";
        Map<String, Object> context = Map.of(
                "userName", "Alice",
                "amount", "50.00",
                "currency", "USD"
        );

        String result = engine.render(template, context);
        assertThat(result).isEqualTo("Hello Alice, your payment of 50.00 USD was successful.");
    }

    @Test
    @DisplayName("Null template returns empty string")
    void nullTemplate() {
        assertThat(engine.render(null, Map.of("key", "val"))).isEmpty();
    }

    @Test
    @DisplayName("Empty context strips all {{varName}} placeholders")
    void emptyContextStripsPlaceholders() {
        String template = "Welcome {{name}}! Token: {{token}}";
        assertThat(engine.render(template, Map.of())).isEqualTo("Welcome ! Token: ");
        assertThat(engine.render(template, null)).isEqualTo("Welcome ! Token: ");
    }

    @Test
    @DisplayName("Unknown variables in template are replaced with empty strings without error")
    void unknownVariables() {
        String template = "Item {{item}} in order {{orderId}} from {{store}}.";
        Map<String, Object> context = Map.of("orderId", "ORD-999");

        String result = engine.render(template, context);
        assertThat(result).isEqualTo("Item  in order ORD-999 from .");
    }

    @Test
    @DisplayName("Malicious SpEL and script expressions are treated as pure string literals and not evaluated")
    void expressionsNotEvaluated() {
        String template = "Payload: {{exploit}}";
        Map<String, Object> context = Map.of(
                "exploit", "${T(java.lang.Runtime).getRuntime().exec('calc')}<script>alert('xss')</script>"
        );

        String result = engine.render(template, context);
        assertThat(result).isEqualTo("Payload: ${T(java.lang.Runtime).getRuntime().exec('calc')}<script>alert('xss')</script>");
    }

    @Test
    @DisplayName("Non-printable control characters are stripped during rendering")
    void nonPrintableCharsStripped() {
        String template = "Code: {{code}}";
        Map<String, Object> context = Map.of(
                "code", "ABC\u0000\u0007\u001BDEF"
        );

        String result = engine.render(template, context);
        assertThat(result).isEqualTo("Code: ABCDEF");
    }
}
