#!/usr/bin/bash

echo #### Step 1: Install Basic Packages (Start) ####
sudo apt install python3-pip
pip install nltk
pip install numpy
pip install pandas
echo ####  Step 1: Install Basic Packages (End) ####

echo ####  Step 2: Download Dataset Sources (Start) ####
mkdir ~/src/pg-view/experiment/dataset/sources/soc -p
cd ~/src/pg-view/experiment/dataset/sources/soc
wget https://nrvis.com/download/data/soc/soc-twitter-follows.zip
unzip soc-twitter-follows.zip
rm soc-twitter-follows.zip

~/src/pg-view/experiment/install_nltk.py

mkdir ~/src/pg-view/experiment/dataset/sources/prov -p
cd ~/src/pg-view/experiment/dataset/sources/prov
wget https://snap.stanford.edu/data/bigdata/wikipedia08/enwiki-20080103.wikipedia_talk.bz2
bzip2 -dk enwiki-20080103.wikipedia_talk.bz2

cd ~/src/pg-view/experiment/dataset/sources
sudo pip3 install gdown
gdown https://drive.google.com/uc?id=1Nk4jD-SXajhD0hrnIsPfi3zI__jug6A7 -O oag.tar.gz
tar xvfz oag.tar.gz
rm oag.tar.gz

cd ~/src/pg-view/experiment/dataset/sources
wget https://repository.surfsara.nl/datasets/cwi/lsqb/files/lsqb-projected/social-network-sf0.3-projected-fk.tar.zst --no-check-certificate
zstd -d social-network-sf0.3-projected-fk.tar.zst
rm social-network-sf0.3-projected-fk.tar.zst
tar xvf social-network-sf0.3-projected-fk.tar
rm social-network-sf0.3-projected-fk.tar
mv social-network-sf0.3-projected-fk lsqb

echo #### Step 2: Download Dataset Sources (End) ####

echo #### Step 3: Convert Dataset Sources into CSV files (Start) ####
cd ~/src/pg-view/experiment
./prep_dataset_sources.py soc
./prep_dataset_sources.py word
./prep_dataset_sources.py prov
./prep_dataset_sources.py oag
./prep_dataset_sources.py lsqb
echo #### Step 3: Convert Dataset Sources into CSV files (End) ####

echo #### Step 4:  Prepare DB snapshots for Experiments (Start) ####
cd ~/src/pg-view/experiment

# Choose which backends to prepare snapshots for.
# DuckDB and PostgreSQL are available without additional software.
# LogicBlox (lb) and Neo4j (n4) require separate installations; omit them
# if those tools are not installed.
PLATFORMS="duck pg"

# Uncomment to include LogicBlox (requires lb services start):
# PLATFORMS="$PLATFORMS lb"

# Uncomment to include Neo4j (requires neo4j-community installation):
# PLATFORMS="$PLATFORMS n4"

./prep_db_snapshots.sh -p $PLATFORMS -d soc oag word prov lsqb
echo #### Step 4:  Prepare DB snapshots for Experiments (End) ####


