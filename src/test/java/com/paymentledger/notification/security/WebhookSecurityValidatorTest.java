package com.paymentledger.notification.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSecurityValidatorTest {

    private final WebhookSecurityValidator validator = new WebhookSecurityValidator();

    @Test
    @DisplayName("Valid public HTTPS webhook URL passes validation")
    void validPublicUrl() {
        assertThatCode(() -> validator.validateUrl("https://api.example.com/webhooks/listener"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Null, empty, or blank webhook URL throws SsrfBlockedException")
    void nullOrBlankUrl() {
        assertThatThrownBy(() -> validator.validateUrl(null))
                .isInstanceOf(SsrfBlockedException.class)
                .hasMessageContaining("cannot be null or empty");

        assertThatThrownBy(() -> validator.validateUrl("   "))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    @DisplayName("Non-HTTP/HTTPS schemes throw SsrfBlockedException")
    void invalidScheme() {
        assertThatThrownBy(() -> validator.validateUrl("ftp://example.com/file"))
                .isInstanceOf(SsrfBlockedException.class)
                .hasMessageContaining("Invalid URL scheme");

        assertThatThrownBy(() -> validator.validateUrl("file:///etc/passwd"))
                .isInstanceOf(SsrfBlockedException.class)
                .hasMessageContaining("Invalid URL scheme");

        assertThatThrownBy(() -> validator.validateUrl("gopher://example.com/"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    @DisplayName("Loopback hostnames (localhost) throw SsrfBlockedException")
    void loopbackHostnames() {
        assertThatThrownBy(() -> validator.validateUrl("http://localhost:8080/webhook"))
                .isInstanceOf(SsrfBlockedException.class)
                .hasMessageContaining("SSRF policy");

        assertThatThrownBy(() -> validator.validateUrl("http://sub.localhost/webhook"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    @DisplayName("Loopback IP addresses (127.0.0.1) throw SsrfBlockedException")
    void loopbackIp() {
        assertThatThrownBy(() -> validator.validateUrl("http://127.0.0.1:8080/hook"))
                .isInstanceOf(SsrfBlockedException.class)
                .hasMessageContaining("blocked");
    }

    @Test
    @DisplayName("Private RFC-1918 IP addresses (10.x, 192.168.x, 172.16.x) throw SsrfBlockedException")
    void privateIps() {
        assertThatThrownBy(() -> validator.validateUrl("http://10.0.0.1/hook"))
                .isInstanceOf(SsrfBlockedException.class)
                .hasMessageContaining("blocked");

        assertThatThrownBy(() -> validator.validateUrl("http://192.168.1.100/hook"))
                .isInstanceOf(SsrfBlockedException.class)
                .hasMessageContaining("blocked");

        assertThatThrownBy(() -> validator.validateUrl("http://172.16.0.5/hook"))
                .isInstanceOf(SsrfBlockedException.class)
                .hasMessageContaining("blocked");
    }

    @Test
    @DisplayName("Cloud metadata IPs (169.254.169.254) and hosts throw SsrfBlockedException")
    void cloudMetadata() {
        assertThatThrownBy(() -> validator.validateUrl("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(SsrfBlockedException.class)
                .hasMessageContaining("blocked");

        assertThatThrownBy(() -> validator.validateUrl("http://metadata.google.internal/computeMetadata/v1/"))
                .isInstanceOf(SsrfBlockedException.class)
                .hasMessageContaining("SSRF policy");
    }
}
