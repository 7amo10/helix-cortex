# Multi-stage Dockerfile
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

COPY pom.xml .
COPY src ./src

# Download PostgreSQL JDBC driver
RUN mvn dependency:copy -Dartifact=org.postgresql:postgresql:42.7.2 -DoutputDirectory=/build/driver/ -B -q

# Package WAR
COPY target/helix-cortex.war* /build/target/

FROM quay.io/wildfly/wildfly:31.0.0.Final-jdk17

USER root
# Configure PostgreSQL JDBC module
RUN mkdir -p $JBOSS_HOME/modules/org/postgresql/main
COPY --from=builder /build/driver/postgresql-42.7.2.jar $JBOSS_HOME/modules/org/postgresql/main/
COPY docker/module.xml $JBOSS_HOME/modules/org/postgresql/main/
RUN chown -R jboss:root $JBOSS_HOME/modules/org/postgresql

# Deploy application and configuration
USER jboss
COPY --from=builder /build/target/helix-cortex.war $JBOSS_HOME/standalone/deployments/
COPY docker/standalone.xml $JBOSS_HOME/standalone/configuration/

EXPOSE 8080
CMD ["/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0"]
