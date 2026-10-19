# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

# ---- 运行阶段 ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# 1883: MQTT 接入层  8080: HTTP / WebSocket
EXPOSE 8080 1883
ENTRYPOINT ["java", "-jar", "app.jar"]
