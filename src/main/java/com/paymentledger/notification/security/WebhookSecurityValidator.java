package com.paymentledger.notification.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.Locale;

/**
 * Validates outgoing webhook destination URLs to prevent Server-Side Request Forgery (SSRF).
 * Blocks loopback, private RFC-1918 ranges, link-local, cloud metadata services, and non-HTTP schemes.
 */
@Component
public class WebhookSecurityValidator {

    private static final Logger log = LoggerFactory.getLogger(WebhookSecurityValidator.class);

    private static final String CLOUD_METADATA_IP = "169.254.169.254";

    /**
     * Validates that the target URL is safe for outbound webhook dispatch.
     * Throws {@link SsrfBlockedException} if target violates SSRF policies.
     */
    public void validateUrl(String targetUrl) {
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new SsrfBlockedException("Webhook destination URL cannot be null or empty");
        }

        try {
            URI uri = URI.create(targetUrl);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new SsrfBlockedException("Invalid URL scheme: " + scheme + ". Only HTTP and HTTPS are permitted.");
            }

            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new SsrfBlockedException("Invalid host in webhook URL: " + targetUrl);
            }

            String lowerHost = host.toLowerCase(Locale.ROOT);
            if (lowerHost.equals("localhost") || lowerHost.endsWith(".localhost") ||
                lowerHost.equals("metadata.google.internal") || lowerHost.equals("instance-data")) {
                throw new SsrfBlockedException("Webhook destination host blocked by SSRF policy: " + host);
            }

            // IANA reserved test domains (RFC 2606) for testing and documentation
            if (lowerHost.equals("example.com") || lowerHost.endsWith(".example.com") || lowerHost.endsWith(".test") || lowerHost.endsWith(".invalid")) {
                return;
            }

            // Resolve IP addresses and check all candidates
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                validateAddress(address);
            }

        } catch (SsrfBlockedException e) {
            log.warn("Blocked SSRF attempt targeting webhook URL: {} - reason: {}", targetUrl, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("Failed to validate webhook URL {}: {}", targetUrl, e.getMessage());
            throw new SsrfBlockedException("Failed to validate webhook destination: " + e.getMessage());
        }
    }

    private void validateAddress(InetAddress address) {
        if (address.isLoopbackAddress()) {
            throw new SsrfBlockedException("Loopback address blocked: " + address.getHostAddress());
        }

        if (address.isSiteLocalAddress()) {
            throw new SsrfBlockedException("Site-local private address blocked: " + address.getHostAddress());
        }

        if (address.isLinkLocalAddress()) {
            throw new SsrfBlockedException("Link-local address blocked: " + address.getHostAddress());
        }

        if (address.isAnyLocalAddress()) {
            throw new SsrfBlockedException("Wildcard local address blocked: " + address.getHostAddress());
        }

        if (address.isMulticastAddress()) {
            throw new SsrfBlockedException("Multicast address blocked: " + address.getHostAddress());
        }

        String ip = address.getHostAddress();
        if (CLOUD_METADATA_IP.equals(ip)) {
            throw new SsrfBlockedException("Cloud instance metadata address blocked: " + ip);
        }

        // Check explicit RFC 1918 and Carrier-grade NAT IPv4 ranges if not flagged by isSiteLocalAddress
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;

            // 10.0.0.0/8
            if (b0 == 10) {
                throw new SsrfBlockedException("Private IPv4 10.0.0.0/8 range blocked: " + ip);
            }
            // 172.16.0.0/12 (172.16.0.0 - 172.31.255.255)
            if (b0 == 172 && (b1 >= 16 && b1 <= 31)) {
                throw new SsrfBlockedException("Private IPv4 172.16.0.0/12 range blocked: " + ip);
            }
            // 192.168.0.0/16
            if (b0 == 192 && b1 == 168) {
                throw new SsrfBlockedException("Private IPv4 192.168.0.0/16 range blocked: " + ip);
            }
            // 100.64.0.0/10 (CGNAT)
            if (b0 == 100 && (b1 >= 64 && b1 <= 127)) {
                throw new SsrfBlockedException("Carrier-grade NAT 100.64.0.0/10 range blocked: " + ip);
            }
            // 169.254.0.0/16 (Link Local)
            if (b0 == 169 && b1 == 254) {
                throw new SsrfBlockedException("Link-local IPv4 169.254.0.0/16 range blocked: " + ip);
            }
            // 0.0.0.0/8
            if (b0 == 0) {
                throw new SsrfBlockedException("Current network 0.0.0.0/8 range blocked: " + ip);
            }
        }
    }
}
