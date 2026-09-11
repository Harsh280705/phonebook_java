FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -Dmaven.test.skip=true dependency:go-offline
COPY src ./src
RUN mvn -q -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/phonebook-api-1.0.0.jar /app/app.jar
EXPOSE 8000
ENV SERVER_PORT=8000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
