# ADR-005: Payment Provider Boundary and Adapter Architecture

## Status
Accepted

## Context
The platform integrates with external payment gateways/processors (e.g. Stripe, Adyen, Banking APIs) to authorize, capture, settle, and refund customer funds. Directly importing external provider SDKs, data structures, or error codes into the core payment or ledger modules couples internal domain logic to third-party vendor contracts. Furthermore, vendor-specific network failures, timeouts, and asynchronous webhook delivery models require strict encapsulation to prevent vendor anomalies from contaminating internal state transitions and ledger rules.

## Decision
1. **Hexagonal / Adapter Isolation**:
   - Establish a strict `PaymentProviderGateway` interface in the payment application layer with vendor-agnostic value objects (`AuthorizeRequest`, `CaptureRequest`, `RefundRequest`, `ProviderTransactionStatus`).
   - Implement vendor-specific adapters (e.g. `SimulatedPaymentProviderAdapter`, `StripeAdapter`) that translate domain commands into external API calls and map vendor responses back into normalized domain outcomes.
   - The core payment and ledger domain models MUST NOT import or reference any external provider SDK, vendor entity, or external HTTP response types.
2. **Provider Responsibilities Isolated in Adapter**:
   - **Credential & Signature Management**: API keys, webhook signing secret verification, and client certificate handling.
   - **Identifier Translation**: Bidirectional mapping between internal UUIDs (`paymentId`, `refundId`) and external provider references (`providerTransactionId`, `chargeId`).
   - **Error Normalization**: Mapping vendor-specific error responses (e.g. `card_declined`, `insufficient_funds`, `network_timeout`, `rate_limit_exceeded`) into platform-standard domain error classifications (`DECLINED`, `INSUFFICIENT_FUNDS`, `NETWORK_TIMEOUT`, `RETRYABLE_ERROR`, `FATAL_ERROR`).
   - **Status Polling / Query API**: Standardized endpoint to actively query external transaction status for timeout recovery.
   - **Webhook Payload Ingestion**: Securely verifying raw webhook signatures, checking timestamp freshness windows, and transforming incoming vendor events into normalized internal domain commands.
3. **Ledger Posting Boundary**:
   - The internal double-entry ledger is **never** posted on authorization. Authorization only reserves purchasing power with the external issuer and updates payment state to `AUTHORIZED`.
   - The ledger is posted **strictly upon confirmed CAPTURE** (or direct settlement confirmation). Funds move in the internal ledger only when an irrevocable financial liability or transfer is confirmed.
4. **Handling Ambiguous Provider Outcomes (Timeout / Loss of Network)**:
   - When the adapter encounters a network timeout, socket disconnect, or gateway 504 during an authorization or capture request:
     - The adapter MUST NOT assume failure (`DECLINED` or `FAILED`).
     - The payment state transitions to `PENDING_RECONCILIATION` (or `UNKNOWN`).
     - The adapter triggers an immediate active status lookup query (using the original idempotency key). If the lookup remains indeterminate, the payment awaits asynchronous webhook confirmation or is flagged for the scheduled reconciliation engine.

## Architectural Diagram
```
┌────────────────────────────────────────────────────────┐
│           Core Payment Domain & State Machine          │
│   (PaymentService, PaymentStateMachine, LedgerService)  │
└───────────────────────────┬────────────────────────────┘
                            │ (Domain Ports / Commands)
                            ▼
┌────────────────────────────────────────────────────────┐
│               PaymentProviderGateway                   │
│        (Vendor-Agnostic Port Interface)                │
└───────────────────────────┬────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────┐
│            Payment Provider Adapter                    │
│  - Encapsulates Vendor SDK / REST Client               │
│  - Maps internal IDs <-> Vendor Reference IDs          │
│  - Verifies Webhook HMAC Signatures                    │
│  - Translates Vendor Errors -> Domain Error Categories │
│  - Manages Connection Pools, Timeouts, Retries         │
└───────────────────────────┬────────────────────────────┘
                            │ (HTTPS / TLS 1.3)
                            ▼
┌────────────────────────────────────────────────────────┐
│              External Payment Provider                 │
└────────────────────────────────────────────────────────┘
```

## Alternatives Considered
1. **Direct Integration of Provider SDKs in Payment Service**:
   - *Rejected*: Strongly couples domain entities with external DTOs, creates vendor lock-in, makes unit testing and simulation cumbersome, and risks leaking vendor changes into core ledger accounting.
2. **Treating Network Timeouts as Immediate Payment Failures**:
   - *Rejected*: In high-latency networks, a provider frequently captures funds successfully but drops the response socket. Marking such payments as `FAILED` creates immediate financial discrepancy where the customer's card is debited but the merchant ledger reflects a cancelled order.

## Consequences
- **Positive**:
  - Domain isolation: changing or adding payment gateways requires only a new adapter implementation.
  - Complete testability: core business logic can be tested using mock or simulated gateway adapters without external dependencies.
  - Robust protection against indeterminate network timeout failures.
- **Negative / Trade-offs**:
  - Additional abstraction layer and mapping boilerplate between domain objects and vendor DTOs.

## Validation
- Failure injection test: Simulate provider returning 200 OK after a 10-second timeout; verify that the adapter catches socket timeout, sets state to `PENDING_RECONCILIATION`, executes status query, and correctly settles without duplicate charging.
