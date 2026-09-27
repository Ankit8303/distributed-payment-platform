package com.paymentledger.messaging.exception;

public class UnsupportedEventVersionException extends RuntimeException {
    public UnsupportedEventVersionException(String version) {
        super("Unsupported event schema version: " + version);
    }
}
