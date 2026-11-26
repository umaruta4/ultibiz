#!/usr/bin/env bash
# UltiBiz infra install on server1.example.com — idempotent
# - Maven 3.9.16 -> /opt/maven (+ /usr/local/bin/mvn)
# - Temurin JDK 21 -> /opt/jdk-21 (apt on server1 is intentionally untouched)
# - Tomcat 11.0.29 -> /opt/tomcat-11 (root-owned CATALINA_HOME)
# - CATALINA_BASE -> /home/janedoe/tomcat-ultibiz (janedoe-owned), localhost:8080
# - MariaDB 11.8 container -> localhost:3311, db/user ultibiz
set -euo pipefail

MVN_VER=3.9.16
TC_VER=11.0.29
TC_BASE=/home/janedoe/tomcat-ultibiz
DATA_DIR=/home/janedoe/data/ultibiz-mariadb
DB_ENV=/home/janedoe/projects/ultibiz/.env

log(){ echo "[$(date '+%H:%M:%S')] $*"; }

# ---------- Maven ----------
if [ ! -x /usr/local/bin/mvn ]; then
  log "installing maven ${MVN_VER}"
  cd /tmp
  curl -fsSL -o maven.tgz "https://archive.apache.org/dist/maven/maven-3/${MVN_VER}/binaries/apache-maven-${MVN_VER}-bin.tar.gz"
  curl -fsSL -o maven.tgz.sha512 "https://archive.apache.org/dist/maven/maven-3/${MVN_VER}/binaries/apache-maven-${MVN_VER}-bin.tar.gz.sha512"
  echo "$(cat maven.tgz.sha512)  maven.tgz" | sha512sum -c -
  rm -rf /opt/apache-maven-${MVN_VER}
  tar -xzf maven.tgz -C /opt
  ln -sfn /opt/apache-maven-${MVN_VER} /opt/maven
  ln -sfn /opt/maven/bin/mvn /usr/local/bin/mvn
  rm -f maven.tgz maven.tgz.sha512
fi
/usr/local/bin/mvn -v | head -2

# ---------- JDK 21 (Temurin tarball; apt is left alone) ----------
if [ ! -x /opt/jdk-21/bin/javac ]; then
  log "installing Temurin JDK 21"
  cd /tmp
  curl -fsSL -o jdk21.tgz -m 560 "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  rm -rf /opt/jdk-21
  mkdir -p /opt/jdk-21
  tar -xzf jdk21.tgz -C /opt/jdk-21 --strip-components=1
  rm jdk21.tgz
fi
/opt/jdk-21/bin/javac -version

# ---------- Tomcat ----------
if [ ! -d /opt/tomcat-11 ]; then
  log "installing tomcat ${TC_VER}"
  cd /tmp
  curl -fsSL -o tomcat.tgz "https://archive.apache.org/dist/tomcat/tomcat-11/v${TC_VER}/bin/apache-tomcat-${TC_VER}.tar.gz"
  curl -fsSL -o tomcat.tgz.sha512 "https://archive.apache.org/dist/tomcat/tomcat-11/v${TC_VER}/bin/apache-tomcat-${TC_VER}.tar.gz.sha512"
  TC_EXPECTED=$(awk '{print $1}' tomcat.tgz.sha512)
  TC_ACTUAL=$(sha512sum tomcat.tgz | awk '{print $1}')
  [ "$TC_EXPECTED" = "$TC_ACTUAL" ] || { echo "TOMCAT SHA512 MISMATCH"; exit 1; }
  rm -rf /opt/apache-tomcat-${TC_VER}
  tar -xzf tomcat.tgz -C /opt
  ln -sfn /opt/apache-tomcat-${TC_VER} /opt/tomcat-11
  rm -f tomcat.tgz tomcat.tgz.sha512
fi
chmod -R o+rX /opt/apache-tomcat-${TC_VER}
chmod +x /opt/tomcat-11/bin/*.sh

# ---------- CATALINA_BASE ----------
install -d -o janedoe -g janedoe ${TC_BASE}/{bin,conf,lib,logs,temp,webapps,work}
if [ ! -f ${TC_BASE}/conf/server.xml ]; then
  cp -a /opt/tomcat-11/conf/. ${TC_BASE}/conf/
fi
rm -rf ${TC_BASE}/webapps/ROOT ${TC_BASE}/webapps/docs ${TC_BASE}/webapps/examples ${TC_BASE}/webapps/host-manager

cat > ${TC_BASE}/conf/server.xml <<'XMLEOF'
<?xml version="1.0" encoding="UTF-8"?>
<Server port="18005" address="localhost" shutdown="SHUTDOWN">
  <Listener className="org.apache.catalina.core.AprLifecycleListener" SSLEngine="on" />
  <Listener className="org.apache.catalina.core.JreMemoryLeakPreventionListener" />
  <Listener className="org.apache.catalina.mbeans.GlobalResourcesLifecycleListener" />
  <Listener className="org.apache.catalina.core.ThreadLocalLeakPreventionListener" />
  <Service name="Catalina">
    <Connector port="8080" address="0.0.0.0" protocol="HTTP/1.1"
               connectionTimeout="20000" maxThreads="150" minSpareThreads="10"
               enableLookups="false" compression="on" compressibleMimeType="text/html,text/xml,text/plain,text/css,text/javascript,application/javascript,application/json" />
    <Engine name="Catalina" defaultHost="localhost">
      <Realm className="org.apache.catalina.realm.LockOutRealm">
        <Realm className="org.apache.catalina.realm.UserDatabaseRealm" resourceName="UserDatabase"/>
      </Realm>
      <Host name="localhost" appBase="webapps" unpackWARs="true" autoDeploy="true">
        <Valve className="org.apache.catalina.valves.AccessLogValve" directory="logs"
               prefix="localhost_access_log" suffix=".txt"
               pattern="%h %l %u %t &quot;%r&quot; %s %b %D" />
      </Host>
    </Engine>
  </Service>
</Server>
XMLEOF

cat > ${TC_BASE}/bin/setenv.sh <<'ENVEOF'
#!/usr/bin/env bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export CATALINA_OPTS="-Xms96m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -Djava.awt.headless=true -Dfile.encoding=UTF-8"
ENVEOF
chmod +x ${TC_BASE}/bin/setenv.sh
chown -R janedoe:janedoe ${TC_BASE}

# ---------- systemd unit ----------
cat > /etc/systemd/system/ultibiz-tomcat.service <<'UNITEOF'
[Unit]
Description=UltiBiz Tomcat 11
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
User=janedoe
Group=janedoe
Environment=JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
Environment=CATALINA_HOME=/opt/tomcat-11
Environment=CATALINA_BASE=/home/janedoe/tomcat-ultibiz
EnvironmentFile=-/home/janedoe/projects/ultibiz/.env
ExecStart=/opt/tomcat-11/bin/catalina.sh run
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
LimitNOFILE=8192

[Install]
WantedBy=multi-user.target
UNITEOF
systemctl daemon-reload

# ---------- MariaDB 11.8 container ----------
install -d -o janedoe -g janedoe $(dirname ${DATA_DIR}) ${DATA_DIR}
if [ ! -f ${DB_ENV} ]; then
  DB_ROOT_PW=$(openssl rand -hex 16)
  DB_APP_PW=$(openssl rand -hex 16)
  cat > ${DB_ENV} <<ENVEOF
ULTIBIZ_DB_HOST=localhost
ULTIBIZ_DB_PORT=3311
ULTIBIZ_DB_NAME=ultibiz
ULTIBIZ_DB_USER=ultibiz
ULTIBIZ_DB_PASSWORD=${DB_APP_PW}
MARIADB_ROOT_PASSWORD=${DB_ROOT_PW}
ULTIBIZ_ADMIN_PASSWORD=$(openssl rand -base64 12 | tr -d '=+/')
ENVEOF
  chmod 600 ${DB_ENV}
  chown janedoe:janedoe ${DB_ENV}
fi
set -a; . ${DB_ENV}; set +a

if ! docker ps -a --format '{{.Names}}' | grep -qx ultibiz-mariadb; then
  log "creating ultibiz-mariadb container (mariadb:11.8)"
  docker run -d --name ultibiz-mariadb --restart unless-stopped \
    -p localhost:3311:3306 \
    -v ${DATA_DIR}:/var/lib/mysql \
    -e MARIADB_ROOT_PASSWORD="${MARIADB_ROOT_PASSWORD}" \
    -e MARIADB_DATABASE=ultibiz \
    -e MARIADB_USER=ultibiz \
    -e MARIADB_PASSWORD="${ULTIBIZ_DB_PASSWORD}" \
    --memory=384m --memory-swap=768m \
    mariadb:11.8 \
    --innodb-buffer-pool-size=128M \
    --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci \
    --skip-log-bin
else
  docker start ultibiz-mariadb >/dev/null 2>&1 || true
fi

log "waiting for mariadb to be ready"
for i in $(seq 1 60); do
  if docker exec ultibiz-mariadb mariadb-admin --silent ping >/dev/null 2>&1; then
    echo "mariadb ready after ${i} polls"; break
  fi
  sleep 2
  if [ "$i" = "60" ]; then echo "MARIADB NOT READY"; exit 1; fi
done

systemctl enable ultibiz-tomcat --quiet
log "DONE: maven, jdk, tomcat, mariadb installed; start with: systemctl start ultibiz-tomcat"
