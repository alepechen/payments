FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :mock-vendor:bootJar --no-daemon

FROM eclipse-temurin:25-jre
COPY --from=build /src/mock-vendor/build/libs/*.jar /app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app.jar"]
