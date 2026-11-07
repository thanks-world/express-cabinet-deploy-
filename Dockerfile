from maven:3.9-eclipse-temurin-17 as builder
workdir /app
copy pom.xml .
run mvn dependency:go-offline
copy src ./src
run mvn clean package -DskipTests

from eclipse-temurin:17-jre-alpine
workdir /app
copy --from=builder /app/target/classes /app/classes
copy --from=builder /app/target/lib /app/lib
expose 5020 8090
cmd ["java", "-cp", "classes:lib/*", "SoftwareA", "--headless"]
