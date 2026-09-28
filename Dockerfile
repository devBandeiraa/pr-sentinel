# Dockerfile único para todos os serviços. O módulo é escolhido via build arg:
#   docker build --build-arg MODULE=webhook-gateway -t pr-sentinel/webhook-gateway .

FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /workspace

# Copia os poms primeiro para aproveitar o cache de dependências
COPY pom.xml .
COPY common/pom.xml common/
COPY webhook-gateway/pom.xml webhook-gateway/
COPY diff-fetcher/pom.xml diff-fetcher/
COPY review-agent/pom.xml review-agent/
COPY orchestrator/pom.xml orchestrator/
COPY github-publisher/pom.xml github-publisher/
RUN mvn -B -q -pl ${MODULE} -am dependency:go-offline

COPY . .
RUN mvn -B -q -pl ${MODULE} -am package -DskipTests \
    && cp ${MODULE}/target/${MODULE}-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
