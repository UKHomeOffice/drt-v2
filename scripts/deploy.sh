#!/usr/bin/env bash

while getopts ":a:b:h:p:" opt; do
  case $opt in
    a)
      ARTIFACT_REPO_URL=$OPTARG
      ;;
    b)
      PIPELINE_BUILD_ID=$OPTARG
      ;;
    h)
      JVA_HOST=$OPTARG
      ;;
    p)
      PORT_CODE=$OPTARG
      ;;
    \?)
      echo "Invalid option: -$OPTARG" >&2
      ;;
  esac
done

echo PIPELINE_BUILD_ID ${PIPELINE_BUILD_ID}
echo PORT_CODE ${PORT_CODE}
TARGET_NAME=drt-${PIPELINE_BUILD_ID}
TARGET_NAME_ZIP="${PORT_CODE}-${TARGET_NAME}.zip"
ARTIFACT_PATH=server/target/universal/$TARGET_NAME_ZIP

#Download build artifact to target location
curl ${ARTIFACT_REPO_URL}/${ARTIFACT_PATH} > $TARGET_NAME_ZIP

scp $TARGET_NAME_ZIP ci-build@${JVA_HOST}:/home/ci-build/
ssh ci-build@${JVA_HOST} "sudo unzip /home/ci-build/${TARGET_NAME_ZIP} -d /usr/share/drt-v2/${PORT_CODE}/"
ssh ci-build@${JVA_HOST} "sudo rm -f /usr/share/drt-v2/${PORT_CODE}/current && sudo ln -s /usr/share/drt-v2/${PORT_CODE}/${TARGET_NAME} /usr/share/drt-v2/${PORT_CODE}/current"
ssh ci-build@${JVA_HOST} "sudo service drt-v2-${PORT_CODE} restart"

#clean up
ssh ci-build@${JVA_HOST} "sudo rm /home/ci-build/${TARGET_NAME_ZIP}"
