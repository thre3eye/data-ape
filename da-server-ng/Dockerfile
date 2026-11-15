FROM eclipse-temurin:21-jre

WORKDIR /app
COPY da-server-ng/target/classes/ /app/classes/
COPY da-server-ng/target/dependency/ /app/lib/
COPY da-server-ng/container.properties /app/container.properties
COPY da-frontend-ng/dist/data-ape/ /web-root/
RUN mkdir -p /docker-data/data-ape/web_config

USER 10001:10001
EXPOSE 4100
ENTRYPOINT ["java", "-cp", "/app/classes:/app/lib/*", "com.isoplane.dataape.ng.DataApeServer"]
CMD ["/docker-data/data-ape/config/da.properties,/app/container.properties"]
