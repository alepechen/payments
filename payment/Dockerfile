FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :payment:bootJar --no-daemon

FROM eclipse-temurin:25-jre
COPY --from=build /src/payment/build/libs/*.jar /app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app.jar"]
