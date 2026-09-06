# Multi-stage: build with Maven + JDK 25, run on a slim JRE.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw.cmd pom.xml ./
# maven-wrapper.jar lives under .mvn/wrapper; ensure it's present
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests dependency:go-offline || true
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN addgroup --system app && adduser --system --ingroup app app
COPY --from=build /build/target/*.jar app.jar
# Secrets are env-injected at runtime (compose passes JWT_SECRET /
# PAYMENT_WEBHOOK_SECRET through). The dev defaults below are placeholders
# ONLY so a bare `docker run` boots; real deployments override both —
# the app refuses secrets shorter than 32 chars.
ENV JWT_SECRET=dev-only-jwt-secret-change-me-0123456789abcdef \
    PAYMENT_WEBHOOK_SECRET=dev-only-webhook-secret-change-me-0123456789
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
