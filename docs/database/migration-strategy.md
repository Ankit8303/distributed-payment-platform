# Database Migration Strategy

Use Flyway.

Rules:
- forward-only versioned migrations;
- migrations reviewed like application code;
- avoid destructive changes in the same release as code depending on old columns;
- use expand -> migrate -> contract for risky changes;
- test migrations from a clean database and from a representative existing version;
- production data repair scripts require controlled operational review and must never be casually committed.
