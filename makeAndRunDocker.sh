#!/bin/bash

# Compile TESTAR_dev project with Gradle wrapper
./gradlew distTar

# Stop testar container if it is running
if [ "$(docker ps -q -f name=testar)" ]; then
  docker stop testar
fi

# Remove testar container if it exists
if [ "$(docker ps -aq -f name=testar)" ]; then
  docker rm testar
fi

docker build -t testar-local/testar:latest .

docker run --rm  --net=host --shm-size=512m --name testar \
--mount type=bind,source="/home/lsi/qa/testar-utilization/TESTAR_dev",target=/mnt \
--mount type=bind,source="/home/lsi/qa/testar-utilization/TESTAR_dev/runImage",target=/runImage \
--mount type=bind,source="/home/lsi/qa/testar-utilization/docker/output",target=/testar/bin/output \
--mount type=bind,source="/home/lsi/qa/testar-utilization/docker/settings",target=/testar/bin/settings \
testar-local/testar:latest
