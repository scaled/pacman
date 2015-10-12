#!/bin/sh
#
# Builds and tests (for travis-ci.org)

set -e

if [ -z "$1" ]; then
  echo "Usage: $0 package_url [package_url ...]"
  exit 255
fi

# create and clean our temp build directory
mkdir -p target/spam
SPAM=`cd target/spam ; pwd`
rm -rf $SPAM/*
cd $SPAM

# download the spam tool
rm -f scaled-pacman.jar
wget http://scaled.github.io/scaled-pacman.jar
RUNSPAM="java -jar $SPAM/scaled-pacman.jar" # -d omitted for now

export SCALED_HOME=$SPAM

# install all of the packages
echo "*** Installing: $*"
$RUNSPAM install "$@"

# then run the tests for each package, if we have any
while [ ! -z "$1" ]; do
  PACKAGE=`basename $1 .git`
  TESTDIR=$SPAM/Packages/$PACKAGE/test
  if [ -d $TESTDIR ]; then
    echo "*** Running tests in $PACKAGE..."
    cd $TESTDIR
    $RUNSPAM run $PACKAGE#test org.junit.runner.JUnitCore \
      `find target/classes -name '*Test.class' | \
       sed 's:target/classes/::' | sed 's:.class::' | sed 's:/:.:g'`
  else
    echo "No $TESTDIR. Not running tests."
  fi

  shift
done
