set +x
PATH=$JAVA_HOME/bin:${PATH}
echo $PATH
#echo $NVM_DIR
source /etc/bashrc

echo what node v
node -v
echo what npm v
npm -v

echo what npm-cache
npm-cache install
cd bundle
npm-cache install
cd -
export SBT_OPTS="-Xmx2G -XX:+UseConcMarkSweepGC -XX:+CMSClassUnloadingEnabled -XX:MaxPermSize=2G -Xss2M -Duser.timezone=UTC"
sbt bundle release
