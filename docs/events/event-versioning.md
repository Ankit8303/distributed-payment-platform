# Event Versioning

Events are contracts.

Rules:
- include schemaVersion;
- prefer backward-compatible additions;
- never silently reinterpret an existing field;
- document breaking changes;
- consumers must tolerate expected unknown fields;
- retain correlationId and causationId for tracing;
- use deterministic event IDs for idempotent processing.
