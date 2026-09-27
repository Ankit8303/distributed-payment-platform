# Multi-stage production Dockerfile for Distributed Payment & Ledger Platform
# Stage 1: Runtime base with unprivileged user
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: Install curl/wget for healthchecks and ca-certificates
RUN apk --no-cache add ca-certificates curl tzdata && \
    addgroup -g 10001 appgroup && \
    adduser -u 10001 -G appgroup -s /sbin/nologin -D appuser

# Set working directory
WORKDIR /app

# Copy packaged JAR artifact (built in reproducible CI/CD pipeline)
ARG JAR_FILE=target/payment-ledger-platform.jar
COPY ${JAR_FILE} app.jar

# Enforce ownership and non-root execution
RUN chown -R appuser:appgroup /app
USER appuser:appgroup

# Hardened JVM container options:
# 1. UseContainerSupport ensures JVM respects cgroup memory limits
# 2. MaxRAMPercentage bounds heap to 75% of container RAM
# 3. ExitOnOutOfMemoryError prevents frozen degraded state on heap exhaustion
# 4. Strict security random source
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom -Dspring.profiles.active=prod"

# Expose HTTP service port only
EXPOSE 8080

# Production container healthcheck against liveness probe
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
