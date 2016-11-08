#!/usr/bin/env bash

while getopts ":a:b:h:" opt; do
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
    \?)
      echo "Invalid option: -$OPTARG" >&2
      ;;
  esac
done

echo PIPELINE_BUILD_ID ${PIPELINE_BUILD_ID}
TNAME=drt-${PIPELINE_BUILD_ID}
TNAME_ZIP=${TNAME}.zip
TARGET=server/target/universal/$TNAME_ZIP

curl ${ARTIFACT_REPO_URL}/${TARGET} > $TNAME_ZIP

scp $TNAME_ZIP ci-build@${JVA_HOST}:/home/ci-build/
ssh ci-build@${JVA_HOST} "sudo unzip /home/ci-build/${TNAME_ZIP} -d /usr/share/drt-v2/"
ssh ci-build@${JVA_HOST} "sudo rm -f /usr/share/drt-v2/current && sudo ln -s /usr/share/drt-v2/${TNAME} /usr/share/drt-v2/current"
ssh ci-build@${JVA_HOST} "sudo service drt-v2-edi restart"
