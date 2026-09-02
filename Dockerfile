FROM node:22-alpine AS frontend
WORKDIR /workspace
COPY web/package*.json web/
RUN cd web && npm ci
COPY web web
RUN cd web && npm run build

FROM eclipse-temurin:17-jdk-alpine AS backend
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
COPY --from=frontend /workspace/src/main/resources/static src/main/resources/static
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S opspilot \
    && adduser -S opspilot -G opspilot \
    && mkdir -p /app/data \
    && chown -R opspilot:opspilot /app
COPY --from=backend /workspace/target/opspilot-0.1.0-SNAPSHOT.jar app.jar
USER opspilot
EXPOSE 9900
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
