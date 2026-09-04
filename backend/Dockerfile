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
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
