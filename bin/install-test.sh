#!/usr/bin/env bash
#
# Does a test install and build of Scaled and some standard packages.

BIN=`dirname $0`
ROOT=`cd $BIN/.. ; pwd`

TARGET=$ROOT/target
export SCALED_HOME=$TARGET/test-spam

# clean out and (re)create the test dir
rm -rf $SCALED_HOME
mkdir -p $SCALED_HOME

java -classpath $TARGET/classes scaled.pacman.Bootstrap -d install scaled
