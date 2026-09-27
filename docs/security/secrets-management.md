# Secrets Management

Local development:
- `.env` is ignored;
- `.env.example` contains names only.

CI/staging/production:
- use the platform's secret store or a dedicated secrets manager;
- rotate credentials;
- restrict access by environment;
- never print secrets in logs;
- scan Git history and CI artifacts for accidental secrets.
