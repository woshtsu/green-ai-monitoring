FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN chmod +x mvnw && ./mvnw -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 10001 --create-home monitoring
WORKDIR /app
COPY --from=build --chown=monitoring:monitoring /workspace/target/monitoring-service-0.0.1-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
