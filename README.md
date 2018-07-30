mvn clean package -DSkipTests
1、编译
2、关闭Jenkins
3、把target下的 bdp-job-fan-in.hpi拷贝到$JENKINS_HOME/plugins目录下   并删除 bdp-job-fan-in目录
4、重启Jenkins