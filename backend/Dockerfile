# Optional BuildKit Maven settings support mirrors/proxies; credentials are not required.
FROM eclipse-temurin:21-jdk-noble AS build

WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Cache only the repository, not the whole /root/.m2: the mvnw wrapper
# self-installs its distribution under /root/.m2/wrapper, and a restored
# cache makes that install fail ("inter-device move failed ... Directory
# not empty") when the dist directory already exists.
RUN --mount=type=cache,target=/root/.m2/repository \
    --mount=type=secret,id=zen-maven-settings,target=/root/.m2/settings.xml,required=false \
    sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2/repository \
    --mount=type=secret,id=zen-maven-settings,target=/root/.m2/settings.xml,required=false \
    ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-noble

RUN apt-get update \
    && apt-get upgrade --yes \
    && apt-get install --yes --no-install-recommends curl \
    && echo "runtime apt=$(dpkg-query -W -f='${Version}' apt) libapt-pkg6.0t64=$(dpkg-query -W -f='${Version}' libapt-pkg6.0t64)" \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' apt)" ge "2.8.4ubuntu0.1" \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system aicostops \
    && useradd --system --gid aicostops --home-dir /app --shell /usr/sbin/nologin aicostops \
    && mkdir -p /var/lib/aicostops/mailbox /var/lib/aicostops/invitations \
    && chown -R aicostops:aicostops /var/lib/aicostops

WORKDIR /app
COPY --from=build --chown=aicostops:aicostops /workspace/target/backend-*.jar app.jar
USER aicostops
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
