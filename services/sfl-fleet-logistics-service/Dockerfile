FROM mirror.gcr.io/library/maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY services/pom.xml services/pom.xml
COPY services/sfl-service-common services/sfl-service-common
COPY services/sfl-fleet-logistics-service services/sfl-fleet-logistics-service
# `-pl X -am` only builds this module and what it depends on, but Maven still parses every <module>
# the parent POM declares to construct the reactor graph before applying that filter - so every
# sibling module needs at least its own pom.xml present, even though none of its source is built here
# or ends up in the final image. Verified: a full source COPY is not required for this, just the POM.
COPY services/sfl-facilities-service/pom.xml services/sfl-facilities-service/pom.xml
COPY services/sfl-safety-security-service/pom.xml services/sfl-safety-security-service/pom.xml
COPY services/sfl-portal-service/pom.xml services/sfl-portal-service/pom.xml
# Every service ships the dashboard (see the pom's copy-operations-ui comment) - -Pui is the profile
# that actually builds it (install-node-and-npm, npm install, npm run build), bound at the top of the
# reactor. Without frontend/ in the build context and without -Pui here, this image builds successfully
# but ships with an empty dashboard - the copy-resources step treats a missing dist/ as optional, not
# fatal, so this failure mode is silent.
COPY frontend frontend
# The bundled dashboard is served from this service's own origin (/home/), so it calls the API
# same-origin and signs in through the identity provider. Without these the build fell back to
# .env.production - the seeded, security-off sign-in - which cannot work against a secured service.
ARG VITE_AUTH_PROVIDER=oidc
ARG VITE_SFL_IAM_ISSUER=http://localhost:8080/realms/sfl
ARG VITE_SFL_IAM_CLIENT_ID=sfl-operations-ui
ENV VITE_AUTH_PROVIDER=$VITE_AUTH_PROVIDER \
    VITE_SFL_IAM_ISSUER=$VITE_SFL_IAM_ISSUER \
    VITE_SFL_IAM_CLIENT_ID=$VITE_SFL_IAM_CLIENT_ID \
    VITE_FLEET_API_BASE_URL= \
    VITE_SAFETY_SECURITY_API_BASE_URL= \
    VITE_FACILITIES_API_BASE_URL=
RUN mvn -f services/pom.xml -pl sfl-fleet-logistics-service -am -Pui package -DskipTests

FROM mirror.gcr.io/library/eclipse-temurin:25-jre
RUN groupadd -r spring && useradd -r -g spring spring
WORKDIR /app
COPY --from=build /workspace/services/sfl-fleet-logistics-service/target/sfl-fleet-logistics-service-0.1.0-SNAPSHOT.jar app.jar
RUN chown -R spring:spring /app
USER spring
# Matches application.yml's own default (server.port: ${SFL_PORT:8093}) so a container run with no
# override listens on the port it documents. A deployment that needs a different port - the
# microservices compose file sets 8080 - overrides SFL_PORT at run time, which takes precedence
# over this default.
ENV SFL_PORT=8093
EXPOSE 8093
ENTRYPOINT ["java", "-jar", "app.jar"]
