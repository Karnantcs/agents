FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre AS agent
WORKDIR /app
COPY --from=build /src/target/agentbox.jar /app/agentbox.jar
RUN mkdir -p /workspace
ENV AGENTBOX_MODE=agent \
    SERVER_PORT=8092 \
    WORKSPACE=/workspace
EXPOSE 8092
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/agentbox.jar"]

FROM eclipse-temurin:21-jre AS orchestrator
WORKDIR /app
COPY --from=build /src/target/agentbox.jar /app/agentbox.jar
ENV AGENTBOX_MODE=orchestrator \
    SERVER_PORT=8091
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/agentbox.jar"]
