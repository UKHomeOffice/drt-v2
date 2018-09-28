#!/bin/bash

while getopts ":u:p:" opt; do
  case $opt in
    u)
      USERNAME=$OPTARG
      ;;
    p)
      PASSWORD=$OPTARG
      ;;
  esac
done

# Get log in and parse cookie
COOKIE=`curl -s -D - 'https://gateway.baa.com/' -H 'User-Agent: Mozilla/5.0' --data 'user='$USERNAME'&password=

# Get latest feed filename
LATEST_FEED_FILENAME=`curl -s 'https://gateway.baa.com/' -H 'User-Agent: Mozilla/5.0' --insecure -H 'Cookie: '$

# Download the file and output the contents
curl -s 'https://gateway.baa.com/'$LATEST_FEED_FILENAME --insecure -H 'Cookie: '$COOKIE

exit 0
