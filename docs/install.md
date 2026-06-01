# Setup and Installation

This guide covers installing PGVIEW on Ubuntu 22.04 LTS. Two backend options are supported: **PostgreSQL** (original) and **DuckDB** (new, no server required). LogicBlox is a legacy backend that is no longer publicly available; its instructions are retained for reference.

---

## Prerequisites

```bash
sudo apt-get install openjdk-21-jdk maven unzip
```

Verify:
```bash
java -version   # should show 21+
mvn -version
```

---

## Z3 (required for type checking)

```bash
mkdir -p ~/tools && cd ~/tools
wget https://github.com/Z3Prover/z3/releases/download/z3-4.8.7/z3-4.8.7-x64-ubuntu-16.04.zip
unzip z3-4.8.7-x64-ubuntu-16.04.zip

mvn install:install-file \
  -Dfile=${HOME}/tools/z3-4.8.7-x64-ubuntu-16.04/bin/com.microsoft.z3.jar \
  -DgroupId=com.microsoft \
  -DartifactId=z3 \
  -Dversion=4.8.7 \
  -Dpackaging=jar \
  -DgeneratePom=true
```

---

## Option A — DuckDB backend (recommended, no server required)

DuckDB is bundled as a JDBC dependency; no separate installation is needed. The only configuration required is choosing a directory for database files.

Edit `conf/graphview.conf`:
```ini
[duckdb]
dbdir = duckdbdata   # directory where .duckdb files will be stored
```

Build and start:
```bash
mvn compile
mvn exec:java@console
```

At the PGVIEW console, connect with:
```
connect duck
```

### Preparing experiment datasets for DuckDB

```bash
cd experiment
./setup.sh                          # downloads and converts datasets to CSV
./prep_db_snapshots.sh -p duck -d word soc prov oag lsqb
```

Run experiments:
```bash
./run.sh -i 1 -p duck -v mv -d word
```

---

## Option B — PostgreSQL backend

### Install PostgreSQL 14

```bash
sudo apt install postgresql-14
```

### Configure PostgreSQL

```bash
sudo su - postgres
psql -U postgres -c "ALTER USER postgres WITH PASSWORD 'postgres@';"
exit

# Change 'peer' to 'md5' in pg_hba.conf:
sudo vi /etc/postgresql/14/main/pg_hba.conf
# local   all   postgres   md5

sudo service postgresql restart
```

Edit `conf/graphview.conf`:
```ini
[postgres]
ip = 127.0.0.1
port = 5432
username = postgres
password = postgres@
```

Build and start:
```bash
mvn compile
mvn exec:java@console
```

At the PGVIEW console:
```
connect pg
```

### Preparing experiment datasets for PostgreSQL

```bash
cd experiment
./setup.sh
./prep_db_snapshots.sh -p pg -d word soc prov oag lsqb
./run.sh -i 1 -p pg -v mv -d word
```

---

## Option C — LogicBlox backend (legacy, no longer available)

LogicBlox 4.41.0 was a commercial Datalog-native DBMS. It is no longer publicly available, so this backend cannot be used without a prior installation. Instructions are retained for reference only.

```bash
# Download from web archive (may not be available):
cd ~/tools
wget https://web.archive.org/web/20230723162235/https://developer.logicblox.com/wp-content/uploads/2022/04/logicblox-linux-4.41.0.tar_.gz
tar xvfz logicblox-linux-4.41.0.tar_.gz
mv logicblox-x86_64-linux-4.41.0-*/ logicblox

source ~/tools/logicblox/etc/profile.d/logicblox.sh
export LB_MEM=12G
lb services start
```

---

## Neo4j backend (optional, for comparison experiments)

Neo4j is embedded in PGVIEW so no separate installation is needed for running queries. A standalone installation is only required for building experiment snapshots.

```bash
cd ~/tools
wget https://dist.neo4j.org/neo4j-community-4.1.11-unix.tar.gz
tar xvfz neo4j-community-4.1.11-unix.tar.gz
```

Edit `conf/graphview.conf`:
```ini
[neo4j]
embedded = true
dbdir = neo4jdata
neo4j.dir = ~/tools/neo4j-community-4.1.11
```

---

## Running tests

```bash
mvn test
```

Tests use in-memory DuckDB instances and require no external services.
