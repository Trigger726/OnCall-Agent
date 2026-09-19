FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw ./
COPY integration/trace-provider-fixture/pom.xml ./
COPY integration/trace-provider-fixture/src src
RUN chmod +x mvnw && ./mvnw -q -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S tracefixture && adduser -S tracefixture -G tracefixture
COPY --from=build /workspace/target/trace-provider-fixture-0.1.0-SNAPSHOT.jar app.jar
USER tracefixture
EXPOSE 9910
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
