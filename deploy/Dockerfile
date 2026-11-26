# syntax=docker/dockerfile:1
# UltiBiz all-in-one image: Maven build -> Tomcat 11 runtime.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies first for better layer caching.
COPY pom.xml ./
COPY ultibiz-core/pom.xml ultibiz-core/pom.xml
COPY ultibiz-spi/pom.xml ultibiz-spi/pom.xml
COPY ultibiz-web/pom.xml ultibiz-web/pom.xml
RUN mvn -B -q dependency:go-offline

COPY ultibiz-core/src ultibiz-core/src
COPY ultibiz-spi/src ultibiz-spi/src
COPY ultibiz-web/src ultibiz-web/src
RUN mvn -B -q -DskipTests package

FROM tomcat:11.0.29-jre21-temurin-noble

RUN groupadd --gid 10001 ultibiz \
 && useradd --uid 10001 --gid 10001 --shell /usr/sbin/nologin ultibiz \
 && rm -rf /usr/local/tomcat/webapps/* \
 && printf '%s\n' \
      'JAVA_OPTS="$JAVA_OPTS -Xms128m -Xmx320m"' \
      > /usr/local/tomcat/bin/setenv.sh \
 && chmod +x /usr/local/tomcat/bin/setenv.sh

COPY --from=build /build/ultibiz-web/target/*.war /usr/local/tomcat/webapps/ROOT.war

RUN chown -R 10001:10001 /usr/local/tomcat
USER 10001

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1
