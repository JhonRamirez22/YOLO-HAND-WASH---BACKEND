FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY backend/pom.xml ./pom.xml
COPY backend/src ./src
RUN mvn --batch-mode --no-transfer-progress \
    -Pcloud-linux-native,!station-macos-arm64-native \
    -DskipTests package

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
RUN apt-get update \
    # libgtk2.0-0: the bundled OpenCV natives (libjniopencv_highgui.so) link against GTK2 at load time, even headless.
    # Without it the first pose evidence fails with UnsatisfiedLinkError (found by the image-smoke CI job).
    && apt-get install --no-install-recommends -y ca-certificates curl libgtk2.0-0 \
    && curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
        --max-time 30 \
        https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem \
        --output /etc/ssl/certs/aws-rds-global-bundle.pem \
    && test -s /etc/ssl/certs/aws-rds-global-bundle.pem \
    && chmod 0444 /etc/ssl/certs/aws-rds-global-bundle.pem \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build --chown=10001:10001 \
    /workspace/target/hand-wash-compliance-1.0.0.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=cloud \
    SERVER_PORT=8080 \
    SERVER_ADDRESS=0.0.0.0 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["java", "-Duser.home=/tmp", "-jar", "/app/app.jar"]
