# syntax=docker/dockerfile:1
# One image = one Tomcat (one JVM) running FIVE applications as WAR files:
#   :8080  Bhai Mobile Billing (MBill)   -> MySQL database bmb
#   :8081  MSchool                       -> MySQL database mschool
#   :8083  mHotel                        -> MySQL database mhotel
#   :8084  Purchase Order Management     -> MySQL database po_mgmt
#   :8082  Meeran Technologies website   -> static, no database
# The runtime stage explodes the WARs and hard-links JARs shared across the Spring Boot apps
# so each dependency is stored once (smaller image); each app still keeps its own class loader.
#
# ---------- build stage: compile suite-security + the modules -> WAR files ----------
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
COPY tomcat/server.xml $CATALINA_HOME/conf/server.xml
# Each WAR is exploded into its own appBase/ROOT directory, so every app keeps its OWN
# WEB-INF/lib -> isolated class loader and the per-module logback-spring.xml stay intact.
# The third-party JARs that are byte-identical across the four Spring Boot apps are then
# hard-linked (rdfind), so the image keeps a single copy of each instead of four. The WARs
# are read over a build-time bind mount, so their bytes never create an image layer; only
# the de-duplicated exploded tree is kept -> smaller image, identical runtime behaviour.
RUN --mount=type=bind,from=build,source=/src,target=/build \
    set -eux; \
    apt-get update; apt-get install -y --no-install-recommends unzip rdfind; \
    rm -rf $CATALINA_HOME/webapps/* $CATALINA_HOME/webapps.dist; \
    mkdir -p $CATALINA_HOME/webapps/ROOT $CATALINA_HOME/webapps-school/ROOT \
             $CATALINA_HOME/webapps-hotel/ROOT $CATALINA_HOME/webapps-purchase/ROOT \
             $CATALINA_HOME/webapps-site/ROOT; \
    unzip -oq /build/bhai-mobile-billing/target/bhai-mobile-billing.war -d $CATALINA_HOME/webapps/ROOT; \
    unzip -oq /build/mschool/target/mschool.war               -d $CATALINA_HOME/webapps-school/ROOT; \
    unzip -oq /build/mhotel/target/mhotel.war                 -d $CATALINA_HOME/webapps-hotel/ROOT; \
    unzip -oq /build/po-mgmt/target/po-mgmt.war               -d $CATALINA_HOME/webapps-purchase/ROOT; \
    unzip -oq /build/website/target/meeran-website.war        -d $CATALINA_HOME/webapps-site/ROOT; \
    rdfind -makehardlinks true -makeresultsfile false \
           $CATALINA_HOME/webapps $CATALINA_HOME/webapps-school $CATALINA_HOME/webapps-hotel \
           $CATALINA_HOME/webapps-purchase $CATALINA_HOME/webapps-site; \
    apt-get purge -y unzip rdfind; apt-get autoremove -y; rm -rf /var/lib/apt/lists/*; \
    useradd --system --uid 10001 --no-create-home bmb; \
    chown -R bmb $CATALINA_HOME
USER bmb
ENV TZ=Asia/Kolkata \
    BMB_BUILD=$BUILD_ID \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/urandom"
EXPOSE 8080 8081 8082 8083 8084
# no curl in the image -> bash /dev/tcp calls /api/ping of all applications
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD bash -c 'for p in 8080 8081 8082 8083 8084; do exec 3<>/dev/tcp/127.0.0.1/$p && printf "GET /api/ping HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "\"ok\":true" <&3 || exit 1; exec 3>&-; done'
CMD ["catalina.sh", "run"]
