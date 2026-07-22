# syntax=docker/dockerfile:1.7

FROM maven:3.9.16-eclipse-temurin-21-noble AS build

WORKDIR /workspace

COPY pom.xml ./
COPY user-service/pom.xml ./user-service/pom.xml
COPY feed-service/pom.xml ./feed-service/pom.xml
COPY search-service/pom.xml ./search-service/pom.xml
COPY notification-service/pom.xml ./notification-service/pom.xml
COPY discovery-server/pom.xml ./discovery-server/pom.xml
COPY api-gateway/pom.xml ./api-gateway/pom.xml

RUN mvn -B -ntp -pl search-service -am dependency:go-offline

COPY search-service/src ./search-service/src

RUN mvn -B -ntp -pl search-service -am package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-noble

WORKDIR /app

COPY --from=build --chown=10001:10001 \
    /workspace/search-service/target/search-service-0.0.1-SNAPSHOT.jar \
    /app/app.jar

USER 10001:10001

EXPOSE 8083

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
