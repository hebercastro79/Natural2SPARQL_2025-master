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

# Instala o Java Runtime Environment (JRE) e as ferramentas de build para o Python
RUN apt-get update && \
    apt-get install -y --no-install-recommends openjdk-17-jre-headless build-essential gcc && \
    rm -rf /var/lib/apt/lists/*

# Copia o arquivo de requisitos
COPY requirements.txt .

# --- CORREÇÃO APLICADA AQUI ---
# Instala as dependências Python, forçando a recompilação de pacotes
# que podem ter problemas de incompatibilidade binária, como o numpy.
RUN pip install --no-cache-dir --no-binary :all: --force-reinstall -r requirements.txt

# Copia o JAR executável criado no estágio anterior
COPY --from=builder /build/target/*.jar app.jar

# Expõe a porta que o Spring Boot usa
EXPOSE 8080

# Comando para iniciar a aplicação Java
ENTRYPOINT ["java", "-jar", "/app/app.jar"]