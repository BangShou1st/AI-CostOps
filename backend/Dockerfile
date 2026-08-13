FROM eclipse-temurin:21-jdk-noble AS build

WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-noble

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system aicostops \
    && useradd --system --gid aicostops --home-dir /app --shell /usr/sbin/nologin aicostops \
    && mkdir -p /var/lib/aicostops/mailbox \
    && chown -R aicostops:aicostops /var/lib/aicostops

WORKDIR /app
COPY --from=build --chown=aicostops:aicostops /workspace/target/backend-*.jar app.jar
USER aicostops
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
