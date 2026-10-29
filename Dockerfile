FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21
ENV TZ="Europe/Oslo"

COPY /build/libs/upload-all.jar app.jar

ENTRYPOINT ["-jar", "app.jar"]
