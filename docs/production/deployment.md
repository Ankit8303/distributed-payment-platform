# Deployment

Baseline:
- immutable container image;
- non-root runtime;
- minimal base image;
- pinned dependency/image versions where practical;
- environment-based configuration;
- secrets injected at runtime;
- database migrations run through controlled deployment;
- health checks;
- graceful shutdown;
- rollback plan;
- reproducible CI build.
