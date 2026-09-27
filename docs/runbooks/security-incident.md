# Operational Runbook: Security Incident & Credential Compromise

**Severity:** SEV-1  
**Target Subsystem:** Authentication, JWT Secret, API Gateways, Security Filters

---

## 1. Symptoms
- Suspected leakage of JWT signing secret (`JWT_SECRET`), database credentials, or provider API keys.
- Detection of unauthorized administrative API access or anomalous API credential stuffing attacks.
- High rate of failed authentication attempts accompanied by suspicious successful logins from unexpected IP ranges.

## 2. Detection
- WAF / SIEM alert: `AnomalousAdminLogin` or `CredentialStuffingDetected`.
- Secret scanner alert on public GitHub repository or commit log.

## 3. Diagnosis
- Identify compromised credential type (JWT secret, provider key, DB password).
- Trace audit logs for operations executed by the suspect token or user:
  ```sql
  SELECT * FROM audit_logs 
  WHERE principal_name = '$SUSPECT_USER' 
  ORDER BY created_at DESC;
  ```

## 4. Commands
```bash
# Rotate JWT secret in secret manager and trigger deployment restart
aws secretsmanager put-secret-value --secret-id prod/jwt_secret --secret-string "$NEW_SECRET"

# Invalidate all active user sessions by revoking refresh tokens
psql $DATABASE_URL -c "UPDATE refresh_tokens SET revoked = true, revoked_at = NOW();"

# Freeze accounts involved in unauthorized operations
curl -X POST -H "Authorization: Bearer $SECURITY_ADMIN_JWT" \
  http://localhost:8080/api/v1/admin/accounts/$COMPROMISED_ACCOUNT/freeze
```

## 5. Safe Actions
- Rotate the compromised secret immediately in Kubernetes secrets / AWS Secrets Manager.
- Revoke all active refresh tokens in the database, forcing all users and sessions to re-authenticate with newly signed JWTs.
- Freeze all affected financial accounts immediately using the Admin Freeze API.
- Block offending IP addresses at the Cloudflare / WAF edge.

## 6. Unsafe Actions
- **NEVER** commit secrets into Git repository during rotation or remediation.
- **NEVER** delay credential rotation while investigating the extent of the breach.

## 7. Rollback
- Not applicable (compromised credentials must remain permanently revoked).

## 8. Verification
- Verify that old JWT tokens signed with previous secret are rejected with HTTP 401 Unauthorized.
- Verify newly issued JWT tokens authenticate successfully.
- Verify all audit logs of affected entities show no further suspicious activity.

## 9. Post-Incident Checks
- Complete forensic audit of all financial movements during the exposure window.
- Notify legal/compliance team if personal or financial data disclosure occurred.
