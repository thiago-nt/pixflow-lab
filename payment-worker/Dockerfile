FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
COPY shared-contracts/pom.xml shared-contracts/pom.xml
COPY outbox-relay/pom.xml outbox-relay/pom.xml
COPY payment-api/pom.xml payment-api/pom.xml
COPY payment-worker/pom.xml payment-worker/pom.xml
COPY psp-simulator/pom.xml psp-simulator/pom.xml
COPY reconciliation-worker/pom.xml reconciliation-worker/pom.xml
COPY shared-contracts/src shared-contracts/src
COPY payment-worker/src payment-worker/src
RUN mvn -B -ntp -pl payment-worker -am package -DskipTests
FROM eclipse-temurin:21.0.9_10-jre-alpine
RUN addgroup -S pixflow && adduser -S -G pixflow pixflow
WORKDIR /app
COPY --from=build /workspace/payment-worker/target/payment-worker-0.1.0-SNAPSHOT.jar app.jar
USER pixflow
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-XX:+ExitOnOutOfMemoryError","-jar","/app/app.jar"]
