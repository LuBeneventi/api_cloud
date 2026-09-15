# Etapa 1: Compilación y empaquetado
FROM maven:3.9-eclipse-temurin-17-alpine AS builder
WORKDIR /app

# Copiar pom.xml y código fuente
COPY pom.xml .
COPY src ./src

# Compilar el artefacto JAR omitiendo pruebas
RUN mvn clean package -DskipTests

# Etapa 2: Imagen final de ejecución
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copiar el artefacto generado
COPY --from=builder /app/target/api-*.jar app.jar

# Puerto expuesto del servicio BFF
EXPOSE 8088

# Comando de inicio
ENTRYPOINT ["java", "-jar", "app.jar"]
  --name bff bff-pedidos360