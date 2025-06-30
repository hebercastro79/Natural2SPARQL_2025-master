# --- ESTÁGIO 1: BUILD DA APLICAÇÃO JAVA COM MAVEN ---
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

# --- ESTÁGIO 2: IMAGEM FINAL DE EXECUÇÃO ---
FROM python:3.9-slim

WORKDIR /app

# Instala o Java Runtime Environment (JRE)
RUN apt-get update && \
    apt-get install -y --no-install-recommends openjdk-17-jre-headless && \
    rm -rf /var/lib/apt/lists/*

# Copia o arquivo de requisitos do código-fonte para a imagem
# O JAR ainda não existe aqui, então copiamos dos fontes
COPY src/main/resources/requirements.txt .

# Instala as dependências Python, incluindo spacy e o modelo
RUN pip install --no-cache-dir -r requirements.txt

# Copia o JAR executável criado no estágio anterior
COPY --from=builder /build/target/*.jar app.jar

# Expõe a porta que o Spring Boot usa
EXPOSE 8080

# Comando para iniciar a aplicação Java
ENTRYPOINT ["java", "-jar", "/app/app.jar"]