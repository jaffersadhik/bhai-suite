# syntax=docker/dockerfile:1
# One image = one Tomcat (one JVM) running BOTH applications as WAR files:
#   :8080  Bhai Mobile Billing (MBill)   -> MySQL database bmb
#   :8081  MSchool                       -> MySQL database mschool
#
# ---------- build stage: compile suite-security + both modules -> two WAR files ----------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY suite-security/pom.xml suite-security/pom.xml
COPY bhai-mobile-billing/pom.xml bhai-mobile-billing/pom.xml
COPY mschool/pom.xml mschool/pom.xml
COPY website/pom.xml website/pom.xml
# dependency cache layer (the sibling module suite-security is built later, so it is excluded; if this step fails the build still works, only slower)
RUN mvn -B -q dependency:go-offline -DexcludeGroupIds=com.bhai || true
COPY suite-security/src suite-security/src
COPY bhai-mobile-billing/src bhai-mobile-billing/src
COPY mschool/src mschool/src
# suite-security (login captcha + IP block) is compiled first and packed into both WARs; the website module has its own image (Dockerfile.website)
RUN mvn -B -q -DskipTests -pl bhai-mobile-billing,mschool -am package

# ---------- runtime stage: Tomcat 10.1 on Java 25 ----------
FROM tomcat:10.1-jre25-temurin-noble
ARG BUILD_ID=""
ENV CATALINA_HOME=/usr/local/tomcat
RUN rm -rf $CATALINA_HOME/webapps/* $CATALINA_HOME/webapps.dist \
 && mkdir -p $CATALINA_HOME/webapps-school \
 && useradd --system --uid 10001 --no-create-home bmb
COPY tomcat/server.xml $CATALINA_HOME/conf/server.xml
COPY --from=build /src/bhai-mobile-billing/target/bhai-mobile-billing.war $CATALINA_HOME/webapps/ROOT.war
COPY --from=build /src/mschool/target/mschool.war $CATALINA_HOME/webapps-school/ROOT.war
RUN chown -R bmb $CATALINA_HOME
USER bmb
ENV TZ=Asia/Kolkata \
    BMB_BUILD=$BUILD_ID \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/urandom"
EXPOSE 8080 8081
# no curl in the image -> bash /dev/tcp calls /api/ping of both applications
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD bash -c 'for p in 8080 8081; do exec 3<>/dev/tcp/127.0.0.1/$p && printf "GET /api/ping HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "\"ok\":true" <&3 || exit 1; exec 3>&-; done'
CMD ["catalina.sh", "run"]
