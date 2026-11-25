# syntax=docker/dockerfile:1
# One image = one Tomcat (one JVM) running THREE applications as WAR files:
#   :8080  Bhai Mobile Billing (MBill)   -> MySQL database bmb
#   :8081  MSchool                       -> MySQL database mschool
#   :8082  Meeran Technologies website   -> static, no database
#
# ---------- build stage: compile suite-security + the three modules -> three WAR files ----------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY suite-security/pom.xml suite-security/pom.xml
COPY bhai-mobile-billing/pom.xml bhai-mobile-billing/pom.xml
COPY mschool/pom.xml mschool/pom.xml
COPY mhotel/pom.xml mhotel/pom.xml
COPY po-mgmt/pom.xml po-mgmt/pom.xml
COPY website/pom.xml website/pom.xml
# dependency cache layer (the sibling module suite-security is built later, so it is excluded; if this step fails the build still works, only slower)
RUN mvn -B -q dependency:go-offline -DexcludeGroupIds=com.bhai || true
COPY suite-security/src suite-security/src
COPY bhai-mobile-billing/src bhai-mobile-billing/src
COPY mschool/src mschool/src
COPY mhotel/src mhotel/src
COPY po-mgmt/src po-mgmt/src
COPY website/src website/src
# suite-security (login captcha + IP block) is compiled first and packed into the MBill and MSchool WARs
RUN mvn -B -q -DskipTests -pl bhai-mobile-billing,mschool,mhotel,po-mgmt,website -am package

# ---------- runtime stage: Tomcat 10.1 on Java 25 ----------
FROM tomcat:10.1-jre25-temurin-noble
ARG BUILD_ID=""
ENV CATALINA_HOME=/usr/local/tomcat
RUN rm -rf $CATALINA_HOME/webapps/* $CATALINA_HOME/webapps.dist \
 && mkdir -p $CATALINA_HOME/webapps-school $CATALINA_HOME/webapps-hotel $CATALINA_HOME/webapps-purchase $CATALINA_HOME/webapps-site \
 && useradd --system --uid 10001 --no-create-home bmb
COPY tomcat/server.xml $CATALINA_HOME/conf/server.xml
COPY --from=build /src/bhai-mobile-billing/target/bhai-mobile-billing.war $CATALINA_HOME/webapps/ROOT.war
COPY --from=build /src/mschool/target/mschool.war $CATALINA_HOME/webapps-school/ROOT.war
COPY --from=build /src/mhotel/target/mhotel.war $CATALINA_HOME/webapps-hotel/ROOT.war
COPY --from=build /src/po-mgmt/target/po-mgmt.war $CATALINA_HOME/webapps-purchase/ROOT.war
COPY --from=build /src/website/target/meeran-website.war $CATALINA_HOME/webapps-site/ROOT.war
RUN chown -R bmb $CATALINA_HOME
USER bmb
ENV TZ=Asia/Kolkata \
    BMB_BUILD=$BUILD_ID \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/urandom"
EXPOSE 8080 8081 8082 8083 8084
# no curl in the image -> bash /dev/tcp calls /api/ping of all applications
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD bash -c 'for p in 8080 8081 8082 8083 8084; do exec 3<>/dev/tcp/127.0.0.1/$p && printf "GET /api/ping HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "\"ok\":true" <&3 || exit 1; exec 3>&-; done'
CMD ["catalina.sh", "run"]
