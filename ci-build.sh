set +x
PATH=$JAVA_HOME/bin:${PATH}
echo $PATH
#echo $NVM_DIR
source /etc/bashrc

echo what node v
node -v
echo what npm v
npm -v

export SBT_OPTS="-Xms8G -Xmx8G -Duser.timezone=UTC"
sbt clean test release
