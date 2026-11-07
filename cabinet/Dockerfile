from maven:3.9.11-eclipse-temurin-17 as builder
workdir /app
copy pom.xml .
copy src ./src
run sed -i 's|<mirrors>|<mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror>|g' /usr/share/maven/conf/settings.xml
run mvn clean package -DskipTests

from eclipse-temurin:17-jre-jammy
workdir /app
copy --from=builder /app/target/express-cabinet-1.0-SNAPSHOT.jar ./app.jar
copy --from=builder /app/target/lib ./lib
expose 5020
entrypoint ["java", "-cp", "app.jar:lib/*"]
cmd ["SoftwareA", "--headless"]
