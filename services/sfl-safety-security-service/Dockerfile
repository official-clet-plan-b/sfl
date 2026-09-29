FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY services/pom.xml services/pom.xml
COPY services/sfl-service-common services/sfl-service-common
COPY services/sfl-safety-security-service services/sfl-safety-security-service
# `-pl X -am` only builds this module and what it depends on, but Maven still parses every <module>
# the parent POM declares to construct the reactor graph before applying that filter - so every
# sibling module needs at least its own pom.xml present, even though none of its source is built here
# or ends up in the final image. Verified: a full source COPY is not required for this, just the POM.
COPY services/sfl-facilities-service/pom.xml services/sfl-facilities-service/pom.xml
COPY services/sfl-fleet-logistics-service/pom.xml services/sfl-fleet-logistics-service/pom.xml
COPY services/sfl-portal-service/pom.xml services/sfl-portal-service/pom.xml
# Every service ships the dashboard (see the pom's copy-operations-ui comment) - -Pui is the profile
# that actually builds it (install-node-and-npm, npm install, npm run build), bound at the top of the
# reactor. Without frontend/ in the build context and without -Pui here, this image builds successfully
# but ships with an empty dashboard - the copy-resources step treats a missing dist/ as optional, not
# fatal, so this failure mode is silent.
COPY frontend frontend
RUN mvn -f services/pom.xml -pl sfl-safety-security-service -am -Pui package -DskipTests

FROM eclipse-temurin:25-jre
RUN groupadd -r spring && useradd -r -g spring spring
WORKDIR /app
RUN mkdir -p /app/logs
COPY --from=build /workspace/services/sfl-safety-security-service/target/sfl-safety-security-service-0.1.0-SNAPSHOT.jar app.jar
RUN chown -R spring:spring /app
USER spring
EXPOSE 8080
# GC logging on by default (rotated, capped at 100MB total) so a HikariCP "thread starvation or
# clock leap" warning - or any other stall - can be checked against real pause data instead of
# guessed at after the fact. No -Xmx/-Xms pinned: JDK 17 reads the container's cgroup memory limit
# itself (MaxRAMPercentage, default 25%) - set docker/orchestrator memory limits deliberately rather
# than duplicating them here, or the two can silently disagree.
ENTRYPOINT ["java", "-Xlog:gc*:file=/app/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=20m", "-jar", "app.jar"]