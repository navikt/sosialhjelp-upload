FROM gcr.io/distroless/java21-debian12

COPY /build/libs/upload-all.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
