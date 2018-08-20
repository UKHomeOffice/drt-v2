#!/usr/bin/env bash

set -o errexit
set -o nounset

# default values
export DRONE_DEPLOY_TO=${DRONE_DEPLOY_TO:?'[error] Please specify which cluster to deploy to.'}
export KUBE_NAMESPACE=${KUBE_NAMESPACE=drt-dev}

export NAME="test"

case ${DRONE_DEPLOY_TO} in
  'acp-notprod')
    export KUBE_SERVER='https://kube-api-notprod.notprod.acp.homeoffice.gov.uk'
    export KUBE_TOKEN=${KUBE_TOKEN_ACP_NOTPROD}
    ;;
esac

echo "--- kube api url: ${KUBE_SERVER}"
echo "--- namespace: ${KUBE_NAMESPACE}"

echo "--- downloading ca for kube api"
if ! curl --silent --fail --retry 5 \
    https://raw.githubusercontent.com/UKHomeOffice/acp-ca/master/${DRONE_DEPLOY_TO}.crt -o /tmp/ca.crt; then
  echo "[error] failed to download ca for kube api"
  exit 1
fi

echo "--- deploying drt-LTN application"


if ! kd --certificate-authority=/tmp/ca.crt \
  --namespace=${KUBE_NAMESPACE} \
  --timeout=5m \
  -f kube/networkpolicy.yaml \
  -f kube/service.yaml \
  -f kube/deployment.yaml \
  -f kube/ingress.yaml; then
  echo "[error] failed to deploy drt application"
  exit 1
fi
