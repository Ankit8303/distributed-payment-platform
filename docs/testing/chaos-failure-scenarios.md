# Failure Scenario Catalogue

Test controlled failures:
- PostgreSQL connection loss during request;
- transaction rollback after domain validation;
- Kafka unavailable after DB commit;
- duplicate Kafka delivery;
- outbox publisher crash;
- Redis unavailable;
- consumer crash after processing but before acknowledgment;
- provider timeout;
- provider success followed by network timeout;
- concurrent refund requests;
- application restart during financial operation.

Expected behavior must preserve invariants and avoid duplicate financial effects.
