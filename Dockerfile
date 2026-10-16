FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --gid 10001 attendance && useradd --uid 10001 --gid 10001 --create-home attendance \
    && mkdir -p /app/output /app/config && chown -R attendance:attendance /app/output /app/config
COPY --from=build /build/target/guild-attendance.jar /app/app.jar
USER attendance
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
