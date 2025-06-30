# --- ESTÁGIO 1: BUILD DA APLICAÇÃO JAVA COM MAVEN ---
# Usa uma imagem oficial do Maven com uma versão de Java compatível com Spring Boot 3.
FROM maven:3.9.6-eclipse-temurin-17 AS builder

# Define o diretório de trabalho para o build
WORKDIR /build

# Copia o pom.xml para o cache de dependências do Docker
COPY pom.xml .
RUN mvn dependency:go-offline

# Copia o resto do código fonte da aplicação
COPY src ./src

# Executa o build do Maven. O JAR executável será criado em /build/target/
# A flag -DskipTests acelera o processo de deploy
RUN mvn clean package -DskipTests


# --- ESTÁGIO 2: IMAGEM FINAL DE EXECUÇÃO ---
# Começamos com uma imagem leve de Python
FROM python:3.9-slim

# Define o diretório de trabalho final da aplicação
WORKDIR /app

# Instala o Java Runtime Environment (JRE) necessário para rodar o JAR
RUN apt-get update && \
    apt-get install -y --no-install-recommends openjdk-17-jre-headless && \
    rm -rf /var/lib/apt/lists/*

# Copia o arquivo de requisitos DA RAIZ DO PROJETO para a imagem
COPY requirements.txt .

# Instala as dependências Python, incluindo spacy e o modelo
RUN pip install --no-cache-dir -r requirements.txt

# Copia o JAR executável que foi criado no estágio 'builder' para a imagem final
COPY --from=builder /build/target/*.jar app.jar

# Expõe a porta que o Spring Boot usa por padrão (8080)
EXPOSE 8080

# Comando para iniciar a aplicação Java quando o container for executado
ENTRYPOINT ["java", "-jar", "/app/app.jar"]