# API Error Contract

Use one stable error envelope, for example:

{
  "timestamp": "...",
  "status": 409,
  "code": "IDEMPOTENCY_CONFLICT",
  "message": "Request conflicts with an existing idempotency key.",
  "path": "/api/v1/payments",
  "traceId": "..."
}

Do not expose stack traces, SQL, secrets, internal hostnames, or provider credentials.
