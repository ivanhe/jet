#!/bin/tcsh
#  run jet with default main class (JetTest)
java -cp $JET_HOME/build/main:$JET_HOME/jet-all.jar -Xmx800m -server -DjetHome=$JET_HOME edu.nyu.jet.tipster.AnnotationTool $1 $2 $3 $4 $5 $6 $7 $8 $9 $10 $11 $12 $13 $14 $15 $16
