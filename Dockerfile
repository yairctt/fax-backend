FROM eclipse-temurin:17-jre
WORKDIR /app
COPY fax-backend.jar .
COPY Fax.jar .
EXPOSE 8080
CMD ["java", "-jar", "fax-backend.jar"]