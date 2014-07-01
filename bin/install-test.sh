#!/usr/bin/env bash
#
# Does a test install and build of Scaled and some standard packages.

BIN=`dirname $0`
ROOT=`cd $BIN/.. ; pwd`

TARGET=$ROOT/target
TESTDIR=$TARGET/test-spam

# clean out and (re)create the test dir
rm -rf $TESTDIR
mkdir -p $TESTDIR

LSPAM="$BIN/lspam -d -Dscaled.meta=$TESTDIR"
$LSPAM install scaled
