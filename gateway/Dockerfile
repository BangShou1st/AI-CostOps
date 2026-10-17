# The OpenCode Zen shared library is consumed from GitHub Packages, so the Maven build needs a
# settings.xml carrying the "github" server credentials. It is mounted as a BuildKit secret so the
# PAT never persists in an image layer (BuildKit is the default builder on modern Docker and on the
# GitHub runners). Supply it explicitly, for example:
#   docker build --secret id=zen-maven-settings,src="$HOME/.m2/settings.xml" gateway

FROM eclipse-temurin:21-jdk-noble AS build

WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=secret,id=zen-maven-settings,target=/root/.m2/settings.xml,required=false \
    sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline
COPY src/ src/
RUN --mount=type=secret,id=zen-maven-settings,target=/root/.m2/settings.xml,required=false \
    ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-noble

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system aicostops \
    && useradd --system --gid aicostops --home-dir /app --shell /usr/sbin/nologin aicostops

WORKDIR /app
COPY --from=build --chown=aicostops:aicostops /workspace/target/gateway-*.jar app.jar
USER aicostops
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
