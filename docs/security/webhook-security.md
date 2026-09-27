# Webhook Security

External payment-provider webhooks must:
- verify cryptographic signatures;
- reject stale/replayed requests where provider semantics support it;
- persist provider event IDs for deduplication;
- process state changes transactionally;
- return provider-compatible acknowledgements;
- avoid trusting arbitrary client-supplied payment status;
- record audit/correlation information without logging secrets.
