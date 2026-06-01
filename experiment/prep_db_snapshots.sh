#!/bin/bash

configtemp=config7.temp
sed -e 's/#.*//' -e '/^$/d' ../conf/graphview.conf | awk '/^\[/ { app=substr($0,2,length-2) } /=/ { print app "." $0 }' > ${configtemp}
neo4jFolder=$(cat ${configtemp} | grep 'neo4j.dir' | cut -d "=" -f2 | awk '{$1=$1};1')
LB_BASEDIR=$(cat ${configtemp} | grep 'logicblox.lb_bin_dir' | cut -d "=" -f2 | awk '{$1=$1};1')
LB_BASEDIR=${LB_BASEDIR/\~/${HOME}}

rm ${configtemp}

# arr=("prov" "oag" "soc" "word" "lsqb")

unset -v platforms
unset -v datasets
while getopts ":p:d:" opt
   do
     case $opt in
        p ) platforms=("$OPTARG")
            until [[ $(eval "echo \${$OPTIND}") =~ ^-.* ]] || [ -z $(eval "echo \${$OPTIND}") ]; do
                platforms+=($(eval "echo \${$OPTIND}"))
                OPTIND=$((OPTIND + 1))
            done
            ;;
        d ) datasets=("$OPTARG")
            until [[ $(eval "echo \${$OPTIND}") =~ ^-.* ]] || [ -z $(eval "echo \${$OPTIND}") ]; do
                datasets+=($(eval "echo \${$OPTIND}"))
                OPTIND=$((OPTIND + 1))
            done
            ;;
     esac
done

# (Start) Settings
if [ -z "$platforms" ] || [ -z "$datasets" ]; then
    printf "Usage: ${0} -p [PLATFORM]... -d [DATASET]...\n\n"
    printf "Examples:\n\t${0} -p lb pg n4 -d word oag\n"
    exit
fi

RUN_LB=false
RUN_PG=false
RUN_N4=false
RUN_DUCK=false

if [[ "${platforms[@]} " =~ "lb" ]]; then
    RUN_LB=true
fi

if [[ "${platforms[@]} " =~ "pg" ]]; then
    RUN_PG=true
fi

if [[ "${platforms[@]} " =~ "n4" ]]; then
    RUN_N4=true
fi

if [[ "${platforms[@]} " =~ "duck" ]]; then
    RUN_DUCK=true
fi

CURPWD=${PWD}
echo "CURPWD: ${CURPWD}"

#arr=("SYN-10000-1000")
#arr=("oag" "prov")



############
# Neo4j
############
if $RUN_N4; then
    echo "[Start] Prepare neo4j database dump"
    echo "neo4jFolder: " $neo4jFolder

    # (Start) Settings
    if [ -z "$neo4jFolder" ]; then
        echo "neo4jFolder is empty"
        exit
    fi

    mkdir -p dataset/targets/snapshot/neo4j

    # neo4jFolder="~/tools/neo4j-community-4.1.11"
    targetsFolder="dataset/targets"
    snapshotsFolder="dataset/snapshots"

    neo4jadmin="${neo4jFolder}/bin/neo4j-admin"
    neo4jdbFolder="${neo4jFolder}/data"

    # dataSets=(word prov soc oag lsqb) # oag word prov soc
    # dataSetsExt=() # syn-sz-10k syn-sz-100k syn-sz-1m syn-sz-10m syn-st-1 syn-st-2 syn-st-4 syn-st-8 syn-st-16 syn-st-32)
    # (End) Settings 

    for w in "${datasets[@]}"
    do
        cmdForRM1="rm -rf ${neo4jdbFolder}/databases/neo4j"
        cmdForRM2="rm -rf ${neo4jdbFolder}/transactions/neo4j"
        echo "$cmdForRM1"
        eval "$cmdForRM1"
        echo "$cmdForRM2"
        eval "$cmdForRM2"

        targetNode="${targetsFolder}/${w}/neo4j/node/node.csv"
        targetEdge="${targetsFolder}/${w}/neo4j/edge/edge.csv"
        cmd="$neo4jadmin import --database=neo4j --id-type=INTEGER --delimiter=\",\" --skip-bad-relationships --nodes=${targetNode} --relationships=${targetEdge}"
        echo "$cmd"
        eval "$cmd"

        neo4jdb="${snapshotsFolder}/neo4j/${w}/neo4j.db"
        cmd="rm -rf ${neo4jdb}"
        echo "$cmd"
        eval "$cmd"

        cmd="mkdir -p ${snapshotsFolder}/neo4j/${w}"
        echo "$cmd"
        eval "$cmd"

        cmd="$neo4jadmin dump --database=neo4j --to ${neo4jdb}"
        echo "$cmd"
        eval "$cmd"

    done

    echo "[End] Prepare neo4j database dump"
fi



############
# DuckDB
############
if $RUN_DUCK; then
    echo "[Start] Prepare DuckDB database snapshots"

    DUCK_SNAPSHOT_DIR="dataset/snapshots/duckdb"
    mkdir -p ${DUCK_SNAPSHOT_DIR}

    # Read dbdir from graphview.conf (default: duckdbdata)
    DUCK_DBDIR=$(grep -A5 '^\[duckdb\]' ../conf/graphview.conf | grep 'dbdir' | cut -d'=' -f2 | awk '{$1=$1};1')
    DUCK_DBDIR=${DUCK_DBDIR:-duckdbdata}
    mkdir -p ${DUCK_DBDIR}

    for item in "${datasets[@]}"
    do
        echo "Start DuckDB dataset=${item}"
        date

        DUCK_FILE="${DUCK_DBDIR}/temp_${item}.duckdb"
        SNAPSHOT_FILE="${DUCK_SNAPSHOT_DIR}/${item}.duckdb"

        CSV_BASE="${CURPWD}/dataset/targets/${item}"
        CSV_NODE="${CSV_BASE}/node.csv"
        CSV_EDGE="${CSV_BASE}/edge.csv"

        # Remove any previous temp db
        rm -f "${DUCK_FILE}"

        # Load data into a DuckDB file via inline SQL
        duckdb "${DUCK_FILE}" <<SQL
CREATE TABLE n_g (_0 INTEGER NOT NULL, _1 VARCHAR(1024));
CREATE TABLE e_g (_0 INTEGER NOT NULL, _1 INTEGER NOT NULL, _2 INTEGER NOT NULL, _3 VARCHAR(1024));
COPY n_g (_0, _1) FROM '${CSV_NODE}' (FORMAT CSV, HEADER TRUE);
COPY e_g (_0, _1, _2, _3) FROM '${CSV_EDGE}' (FORMAT CSV, HEADER TRUE);
CREATE INDEX n_g__0 ON n_g (_0);
CREATE INDEX n_g__1 ON n_g (_1);
CREATE INDEX e_g__0 ON e_g (_0);
CREATE INDEX e_g__1 ON e_g (_1);
CREATE INDEX e_g__2 ON e_g (_2);
CREATE INDEX e_g__3 ON e_g (_3);
SQL

        cp "${DUCK_FILE}" "${SNAPSHOT_FILE}"
        rm -f "${DUCK_FILE}"
        echo "Saved DuckDB snapshot to ${SNAPSHOT_FILE}"
    done

    echo "[End] Prepare DuckDB database snapshots"
fi


############
# LogicBlox
############

#Import to LB from CSV
if $RUN_LB; then
    # Check that the lb binary is available before proceeding.
    if [ -z "${LB_BASEDIR}" ] || [ ! -f "${LB_BASEDIR}/lb" ]; then
        echo "WARNING: LogicBlox binary not found at [${LB_BASEDIR}/lb]. Skipping LB snapshots."
        echo "         Set logicblox.lb_bin_dir in conf/graphview.conf and ensure lb services are running."
        RUN_LB=false
    fi
fi

if $RUN_LB; then
    echo "LB_BASEDIR: ${LB_BASEDIR}"

    mkdir -p dataset/snapshots/logicblox

    for item in "${datasets[@]}"
    do
        # item="SYN-10000-1000"
        time ${LB_BASEDIR}/lb create ${item} --overwrite
        time ${LB_BASEDIR}/lb addblock ${item} --name schema01 -f datasetlib/prep_includes/block.logic
        sed "s|<FILEPATH>|${CURPWD}/dataset/targets/${item}|g" datasetlib/prep_includes/exec.logic > temp.logic
        # sed -i "s|<FILEPATH>|${CURPWD}/targets/${item}|g" prep/exec.logic > prep/temp.logic # Linux
        # cat prep/temp.logic
        time ${LB_BASEDIR}/lb exec ${item} -f temp.logic
        rm -f temp.logic
        rm -rf dataset/snapshots/logicblox/${item} # if exists
        # Backup (export)
        time ${LB_BASEDIR}/lb export-workspace ${item} dataset/snapshots/logicblox/${item}
        time ${LB_BASEDIR}/lb delete ${item}

        # Restor (import)
        #lb import-workspace ${item}_2 targets/logicblox/${item}
    done
fi


############
# Postgres
############
if $RUN_PG; then
    mkdir -p dataset/snapshots/postgres

    for item in "${datasets[@]}"
    do
        echo Start dataset=${item}
        date

        DB_NAME=temp_dataset
        SQL_BACKUP=dataset/snapshots/postgres/${item}.sql

        CSV_BASE="${CURPWD}/dataset/targets/${item}"
        CSV_NODE="${CSV_BASE}/node.csv" 
        CSV_EDGE="${CSV_BASE}/edge.csv"

        echo "CSV_NODE: ${CSV_NODE}"
        echo "CSV_EDGE: ${CSV_EDGE}"

        time PGPASSWORD=postgres@ psql -X -U postgres -v v0="$DB_NAME" -v v1="$CSV_NODE" -v v2="$CSV_EDGE" -f datasetlib/prep_includes/postgres_snapshot_pre.sql

        sql="\COPY n_g (_0, _1) FROM '${CSV_NODE}' WITH DELIMITER ',' CSV HEADER;"
        time PGPASSWORD=postgres@ psql -X -U postgres -d ${DB_NAME} -c "${sql}"
        sql="\COPY e_g (_0, _1, _2, _3) FROM '${CSV_EDGE}' WITH DELIMITER ',' CSV HEADER;"
        time PGPASSWORD=postgres@ psql -X -U postgres -d ${DB_NAME} -c "${sql}"

        time PGPASSWORD=postgres@ psql -X -U postgres -d ${DB_NAME} -v v0="$DB_NAME" -v v1="$CSV_NODE" -v v2="$CSV_EDGE" -f datasetlib/prep_includes/postgres_snapshot_post.sql
        time PGPASSWORD=postgres@ pg_dump -U postgres $DB_NAME > ${SQL_BACKUP}
        time PGPASSWORD=postgres@ psql -X -U postgres -c "drop database ${DB_NAME}"

        # #DUMP
        # PGPASSWORD=postgres@ psql -X -U postgres -c "create database ${DB_NAME}"
        # time PGPASSWORD=postgres@ psql -X -U postgres $DB_NAME < ${SQL_BACKUP}

        #time pg_dump -U postgres test1 > temp.sql
        #PGPASSWORD=postgres@ psql -X -U postgres -c "create database test2"
        #time PGPASSWORD=postgres@ psql -X -U postgres test2 < temp.sql
    done
fi
