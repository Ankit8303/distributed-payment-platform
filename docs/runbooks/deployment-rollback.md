# Operational Runbook: Safe Deployment Rollback

**Severity:** SEV-1 / SEV-2  
**Target Subsystem:** Continuous Deployment Pipeline / Kubernetes / Docker Container

---

## 1. Symptoms
- Application startup failures following a new container release (`CrashLoopBackOff`).
- Immediate spike in unhandled HTTP 500 errors across API endpoints post-deployment.
- Memory leak or CPU spike observed within 15 minutes of new image rollout.

## 2. Detection
- Kubernetes deployment rollout status reporting unready pods.
- Alert: `DeploymentCanaryErrorRateHigh` or `ContainerRestartSpike`.

## 3. Diagnosis
- Inspect container startup logs:
  `kubectl logs -l app=payment-ledger --tail=200`
- Check whether failure is related to a database migration or application code regression.

## 4. Commands
```bash
# Roll back Kubernetes deployment to previous revision
kubectl rollout undo deployment/payment-ledger-app

# Inspect rollout status
kubectl rollout status deployment/payment-ledger-app

# Verify active version
kubectl describe deployment/payment-ledger-app | grep Image
```

## 5. Safe Actions
- Roll back application container image immediately to the last known healthy digest.
- Ensure database migrations follow the expand-contract pattern (forward and backward compatible), allowing old code to run against the newly migrated schema.
- Terminate any hung pods gracefully (`SIGTERM` allows up to 30s graceful shutdown).

## 6. Unsafe Actions
- **NEVER** run destructive SQL migration down-scripts (`DROP TABLE` / `DROP COLUMN`) during rollback.
- **NEVER** force-kill application instances processing active provider calls without letting graceful shutdown finish.

## 7. Rollback
- Revert Git commit on `main`, triggering automated CI/CD pipeline verification.

## 8. Verification
- Verify pods reach `Running` state and pass `/actuator/health` readiness probe.
- Verify error rate drops back to baseline (< 0.1%).

## 9. Post-Incident Checks
- Quarantine the failed container image tag.
- Conduct local reproduction and test gap analysis.
