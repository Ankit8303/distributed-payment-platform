# Release Strategy

Recommended progression:
1. local development
2. CI verification
3. integration/staging
4. controlled test traffic
5. canary or limited rollout where infrastructure supports it
6. progressive rollout
7. post-release verification
8. rollback if acceptance criteria regress

Database changes must support the deployment sequence; avoid incompatible migrations.
