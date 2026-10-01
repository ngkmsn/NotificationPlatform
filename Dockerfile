FROM eclipse-temurin:21-jre-alpine
WORKDIR /work

# Copy Quarkus Fast-Jar artifacts
COPY target/quarkus-app/lib/ /work/lib/
COPY target/quarkus-app/*.jar /work/
COPY target/quarkus-app/app/ /work/app/
COPY target/quarkus-app/quarkus/ /work/quarkus/

# Copy Firebase credentials if present
COPY firebase-service-account.json* /work/

EXPOSE 8080 8082 8083

ENV JAVA_OPTS="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV QUARKUS_PROFILE=prod

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dquarkus.profile=$QUARKUS_PROFILE -jar /work/quarkus-run.jar"]
