# 第一阶段：Maven编译打包
FROM maven:3.9.11-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
# 阿里云Maven镜像加速
RUN sed -i 's|<mirrors>|<mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror>|g' /usr/share/maven/conf/settings.xml
RUN mvn clean package -DskipTests

# 第二阶段：运行镜像
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app
# 复制编译产物
COPY --from=builder /app/target/http-gateway-1.0-SNAPSHOT.jar ./app.jar
COPY --from=builder /app/target/lib ./lib
# 暴露端口
EXPOSE 8080
# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]
