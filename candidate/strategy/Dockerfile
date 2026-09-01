FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src

RUN mvn -B -DskipTests package dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /build/target/quoter-foundation-1.0.0.jar /app/quoter.jar
COPY --from=build /build/target/dependency /app/lib

CMD ["java", "-cp", "/app/quoter.jar:/app/lib/*", "com.trv.quoter.QuoterMain"]
