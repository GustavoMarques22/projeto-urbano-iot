# Build em duas etapas: compila com Maven e executa só com o JRE
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/borda.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
